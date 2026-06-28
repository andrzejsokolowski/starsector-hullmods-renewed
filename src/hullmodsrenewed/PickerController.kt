package hullmodsrenewed

import com.fs.starfarer.api.loading.HullModSpecAPI
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TextFieldAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color
import hullmodsrenewed.RefitPickerInjector.Companion.findDescendant
import hullmodsrenewed.RefitPickerInjector.Companion.hasMethod
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import hullmodsrenewed.uiframework.AreaCheckbox
import hullmodsrenewed.uiframework.Button
import hullmodsrenewed.uiframework.CustomPanel
import hullmodsrenewed.uiframework.Font
import hullmodsrenewed.uiframework.ReflectionUtils.invoke
import hullmodsrenewed.uiframework.Text
import hullmodsrenewed.uiframework.TextField
import hullmodsrenewed.uiframework.TooltipMakerPanel
import hullmodsrenewed.uiframework.anchorInTopMiddleOfParent
import hullmodsrenewed.uiframework.bottom
import hullmodsrenewed.uiframework.drawBorder
import hullmodsrenewed.uiframework.height
import hullmodsrenewed.uiframework.left
import hullmodsrenewed.uiframework.onClick
import hullmodsrenewed.uiframework.parent
import hullmodsrenewed.uiframework.playSound
import hullmodsrenewed.uiframework.right
import hullmodsrenewed.uiframework.top
import hullmodsrenewed.uiframework.width
import hullmodsrenewed.uiframework.xAlignOffset

/**
 * Operates on an open hull-mod picker (`ModPickerDialogV3`):
 *  - **Filter:** removes rows from the picker's `UITable` each frame (re-applied after the dialog
 *    rebuilds its table). Each criterion is independent and driven by [FilterState] (the left-panel
 *    toggle buttons, persistent) OR'd with a hold-keybind (momentary):
 *      - **Favourites only** — button, or hold **`** (backtick) — keep only favourited mods
 *      - **Show blacklisted** — button, or hold **Alt** — stop hiding blacklisted mods
 *      - **Applicable only** — button — hide mods that can never go on this hull
 *      - **Search** — substring on name / design type
 *      - **Design-type / Type facets** — multi-select (click = only this, Shift/Ctrl+click = add)
 *  - **Mark:** a transparent overlay over the picker turns **Ctrl+click** into "toggle blacklist"
 *    and **Shift+click** into "toggle favourite" on the row under the cursor, consuming the click
 *    so the vanilla dialog doesn't also install the mod. Plain clicks pass straight through.
 */
object PickerController {

    private data class FacetModel(
        val designTypes: List<Pair<String, Int>>,
        val types: List<Pair<String, Int>>,
    )

    private var injectedPicker: UIPanelAPI? = null
    private var leftPanel: CustomPanelAPI? = null
    private var lastSignature = ""
    private var lastShip: Any? = null
    private var searchField: TextFieldAPI? = null
    private var facetModel = FacetModel(emptyList(), emptyList())

    /** The vanilla design-type filter widget (`ModPickerDialogV3.tags`). We hide its bar and keep a
     *  reference so we can keep every mod passing the vanilla filter (our left panel replaces it). */
    private var vanillaTags: Any? = null

    /** Sort preservation: the table's default sort column (first one we ever see) and the column the
     *  player deliberately sorted by. We re-assert the latter after any rebuild re-sorts to default. */
    private var defaultSortColumn: Any? = null
    private var desiredSortColumn: Any? = null
    private var sortResetHandled = false

    /** Cache of "is this hull-mod applicable to the current ship" (id -> applicable); cleared on ship
     *  change and whenever the installed-mods loadout changes (installing/removing a mod can flip other
     *  mods' applicability, e.g. mutually-exclusive pairs). */
    private val applicableCache = HashMap<String, Boolean>()

    /** Fingerprint of the mods installed on the ship last frame. A change means applicability may have
     *  flipped, so we drop the cache and rebuild the table. Only tracked while filtering by
     *  applicability (empty otherwise), so loadout edits don't force needless rebuilds in other modes. */
    private var lastApplicabilityFp = ""

    /** Last mouse position over the picker (UI space), tracked so a number-key press can mark the
     *  hull-mod currently under the cursor into a custom group. */
    private var hoverX = 0f
    private var hoverY = 0f

    /** Group-number keys currently held, so auto-repeat doesn't toggle a mod's group every frame. */
    private val heldGroupKeys = HashSet<Int>()

    /** Number-row keys that mark the hovered mod into the matching custom group: 1-9 then 0 for the
     *  10th group (order matches the on-screen squares). */
    private val groupKeys = intArrayOf(
        Keyboard.KEY_1, Keyboard.KEY_2, Keyboard.KEY_3, Keyboard.KEY_4, Keyboard.KEY_5,
        Keyboard.KEY_6, Keyboard.KEY_7, Keyboard.KEY_8, Keyboard.KEY_9, Keyboard.KEY_0,
    )

    /** Scroll preservation: the hull id + a fingerprint of the loadout (flux vents/capacitors,
     *  installed mods, and our blacklist/favourite/custom-group marks) seen last frame. Lets us tell
     *  an action we want to keep the scroll through (a refit change or a marking) from a hull switch
     *  or a filter change (which both go to the top). */
    private var lastHullId: String? = null
    private var lastLoadoutFp = Int.MIN_VALUE

    fun process(picker: UIPanelAPI) {
        if (picker !== injectedPicker) {
            injectedPicker = picker
            lastSignature = ""
            applicableCache.clear()
            lastApplicabilityFp = ""
            searchField = null
            defaultSortColumn = null
            desiredSortColumn = null
            sortResetHandled = false
            lastHullId = null
            lastLoadoutFp = Int.MIN_VALUE
            heldGroupKeys.clear()
            // Filter selections (search + facets + toggles) are deliberately NOT cleared here, so they
            // persist across closing and reopening the picker within a session, matching vanilla. Use
            // "Reset filters" to clear them. (FilterState is a singleton, so the values just stick.)
            buildFacetModel(picker)
            RefitDebug.dumpTree(picker, "ModPickerDialogV3")
            injectMarkingOverlay(picker)
            injectLeftPanel(picker)
            hideVanillaFilterBar(picker)
        }
        applyFilter(picker)
    }

    // --- Filtering -----------------------------------------------------------------------------

    private fun applyFilter(picker: UIPanelAPI) {
        val table = findTable(picker) ?: return

        // Remember the player's chosen sort so we can restore it after a rebuild (installing a mod
        // rebuilds the table and vanilla re-sorts to its default). The first column we ever observe
        // IS that default; anything different is a deliberate choice. Tracked every frame because the
        // reset happens during the install click, before our updateTable below.
        val curSort = runCatching { table.invoke("getLastSortColumn") }.getOrNull()
        if (defaultSortColumn == null) {
            defaultSortColumn = curSort
        } else if (curSort != null && curSort !== defaultSortColumn) {
            desiredSortColumn = curSort        // player picked a non-default sort
            sortResetHandled = false
        } else if (desiredSortColumn != null && curSort === defaultSortColumn && !sortResetHandled) {
            sortResetHandled = true            // sort was reset to default (e.g. install) -> rebuild w/ restore
            lastSignature = ""
        }

        // Effective = persistent toggle (button) OR momentary hold-key.
        val favOnly = FilterState.favouritesOnly ||
            Keyboard.isKeyDown(Keyboard.KEY_GRAVE)
        val showBlacklisted = FilterState.showBlacklisted ||
            Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)
        val applicableOnly = FilterState.applicableOnly

        // Resolve the ship up front; if it changed (switched hull), drop the applicability cache.
        val ship = if (applicableOnly) resolveShip(picker) else null
        if (ship !== lastShip) {
            applicableCache.clear()
            lastShip = ship
        }

        // Installing or removing a mod can flip other mods' applicability (mutually-exclusive pairs).
        // When that loadout changes, drop the (now-stale) applicability cache. Empty when not filtering
        // by applicability, so loadout edits don't churn the cache or signature in other modes.
        val applicabilityFp = installedModsFingerprint(ship)
        if (applicabilityFp != lastApplicabilityFp) {
            applicableCache.clear()
            lastApplicabilityFp = applicabilityFp
        }

        searchField?.let { FilterState.searchText = it.text?.trim() ?: "" }
        val query = FilterState.searchText.lowercase()
        val selDesign = FilterState.selectedDesignTypes
        val selType = FilterState.selectedTypes

        val blacklist = HullmodPrefs.blacklist()
        val favourites = HullmodPrefs.favourites()

        // Custom-group filter: union of the selected groups' members (OR within), or null for "no group
        // filter". groupTotal (the total membership across all groups) goes in the signature so marking
        // a mod into a group rebuilds the list, and into the loadout fingerprint so it keeps the scroll.
        val selGroups = FilterState.selectedGroups
        val groupUnion: Set<String>? =
            if (selGroups.isEmpty()) null else selGroups.flatMapTo(HashSet()) { HullmodPrefs.groupMembers(it) }
        val groupTotal = (1..HullmodPrefs.GROUP_COUNT).sumOf { HullmodPrefs.groupMembers(it).size }

        // When the effective filter changes, rebuild the table so loosening brings rows back. Ship
        // identity is deliberately NOT in the signature: installing a mod rebuilds the preview ship
        // (new object) but it's the same hull, and rebuilding then would reset the list scroll and
        // sort. Vanilla refreshes the table itself on a real ship switch, so we don't need to.
        // applicabilityFp is included so installing OR removing a mod rebuilds the table while
        // "applicable only" is on -- otherwise a mod that became applicable again (e.g. after removing
        // its mutually-exclusive counterpart) would stay filtered out until the next unrelated rebuild.
        val signature = "$favOnly|$showBlacklisted|$applicableOnly|$query|${selDesign.sorted()}|" +
            "${selType.sorted()}|${selGroups.sorted()}|${blacklist.size}|${favourites.size}|$groupTotal|$applicabilityFp"
        if (signature != lastSignature) {
            // Keep the (now-hidden) vanilla design-type filter wide open so the rebuild shows the full
            // available list; our left-panel filters below are the only ones that trim it. Rebuild via
            // the dialog's own tagsChanged (not plain updateTable) because tagsChanged re-applies the
            // player's sort after rebuilding -- updateTable always reverts to the default sort.
            runCatching { vanillaTags?.invoke("checkAll") }
            runCatching {
                val t = vanillaTags
                if (t != null) picker.invoke("tagsChanged", t) else picker.invoke("updateTable")
            }
            lastSignature = signature
        }

        val rows = table.invoke("getRows") as? List<*>
        if (rows != null) {
            val toRemove = rows.filter { row ->
                val data = runCatching { row?.invoke("getData") }.getOrNull() ?: return@filter false
                val spec = data as? HullModSpecAPI ?: return@filter false
                val id = spec.id
                when {
                    !showBlacklisted && id in blacklist -> true
                    favOnly && id !in favourites -> true
                    applicableOnly && ship != null && !isApplicableToShip(picker, ship, data, id) -> true
                    query.isNotEmpty() && !matchesSearch(spec, query) -> true
                    selDesign.isNotEmpty() && designTypeOf(spec) !in selDesign -> true
                    selType.isNotEmpty() && spec.uiTags.none { it in selType } -> true
                    groupUnion != null && id !in groupUnion -> true
                    else -> false
                }
            }
            if (toRemove.isNotEmpty()) runCatching {
                table.invoke("suspendRecompute")
                toRemove.forEach { table.invoke("removeRow", it) }
                table.invoke("resumeRecompute")
            }
        }

        preserveScroll(picker, table, blacklist.size, favourites.size, groupTotal)
    }

    /**
     * Keeps the list's scroll position across table rebuilds. The picker's `updateTable()` does
     * clear()+re-add and never saves/restores its scroller, so anything that refreshes the picker --
     * installing a mod, adding flux vents/capacitors, or blacklisting/favouriting a mod (which
     * changes our filter signature and so rebuilds the table) -- snaps the list back to the top.
     *
     * We mirror the player's scroll every stable frame (via the scroller's own
     * `saveVerticalScrollState`) and restore it the frame the loadout changes on the SAME hull
     * (a refit change or a blacklist/favourite mark). A real hull switch (different hull id) is left
     * at the top, which is what vanilla does; a plain filter change (toggles/search/facets) leaves
     * the loadout untouched, so it falls through to the stable branch and ends up at the top after
     * its own rebuild.
     */
    private fun preserveScroll(
        picker: UIPanelAPI, table: Any, blacklistSize: Int, favouritesSize: Int, groupTotal: Int,
    ) {
        val variant = runCatching {
            picker.invoke("getRefitPanel")?.invoke("getShipDisplay")?.invoke("getCurrentVariant")
        }.getOrNull() ?: return
        val scroller = runCatching { table.invoke("getList")?.invoke("getScroller") }.getOrNull() ?: return

        val hullId = runCatching { variant.invoke("getHullSpec")?.invoke("getHullId") as? String }.getOrNull()
        val vents = runCatching { variant.invoke("getNumFluxVents") as? Int }.getOrNull() ?: 0
        val caps = runCatching { variant.invoke("getNumFluxCapacitors") as? Int }.getOrNull() ?: 0
        val mods = runCatching { (variant.invoke("getNonBuiltInHullmods") as? Collection<*>)?.size }.getOrNull() ?: 0
        val loadoutFp = vents + caps * 1009 + mods * 1_000_003 +
            blacklistSize * 31 + favouritesSize * 131 + groupTotal * 7919

        when {
            hullId != lastHullId -> {              // switched to a different hull: let it sit at the top
                lastHullId = hullId
                lastLoadoutFp = loadoutFp
            }
            loadoutFp != lastLoadoutFp -> {        // refit change or mark on this hull: keep position
                runCatching { scroller.invoke("restoreVerticalScrollState") }
                lastLoadoutFp = loadoutFp
            }
            else -> {                              // stable frame: remember where the player is
                runCatching { scroller.invoke("saveVerticalScrollState") }
            }
        }
    }

    private fun resetFilters(picker: UIPanelAPI) {
        FilterState.favouritesOnly = false
        FilterState.showBlacklisted = false
        FilterState.applicableOnly = true
        FilterState.clearTransient()             // search text + facet selections
        lastSignature = ""                       // force a table rebuild on the next frame
        leftPanel?.let { runCatching { picker.removeComponent(it) } }
        injectLeftPanel(picker)                  // rebuild so every checkbox/field shows the reset state
        runCatching { playSound("ui_button_pressed") }
    }

    // --- Marking overlay -----------------------------------------------------------------------

    private fun injectMarkingOverlay(picker: UIPanelAPI) {
        val overlay: CustomPanelAPI = picker.CustomPanel(picker.width, picker.height) { plugin ->
            // Remember where the cursor is so a number-key press knows which row it is over.
            plugin.onHover { event -> hoverX = event.x.toFloat(); hoverY = event.y.toFloat() }

            plugin.onClick { event ->
                if (!event.isLMBDownEvent) return@onClick
                val ctrl = event.isCtrlDown
                val shift = event.isShiftDown
                if (!ctrl && !shift) return@onClick // let vanilla handle normal clicks

                val spec = rowUnderCursor(picker, event.x.toFloat(), event.y.toFloat())?.specOrNull()
                    ?: return@onClick

                if (ctrl) HullmodPrefs.toggleBlacklist(spec.id) else HullmodPrefs.toggleFavourite(spec.id)
                runCatching { playSound("ui_button_pressed") } // audible confirmation; never fatal
                event.consume()
            }

            // Hover a mod and press 1..8 to toggle it in that custom group (RTS-style control groups).
            plugin.onKeyDown { event ->
                if (searchField?.hasFocus() == true) return@onKeyDown // typing in the search box, not marking
                val group = groupKeyToIndex(event.eventValue) ?: return@onKeyDown
                if (!heldGroupKeys.add(event.eventValue)) return@onKeyDown // ignore key auto-repeat
                val spec = rowUnderCursor(picker, hoverX, hoverY)?.specOrNull() ?: return@onKeyDown
                HullmodPrefs.toggleGroup(group, spec.id)
                runCatching { playSound("ui_button_pressed") }
                event.consume()
            }
            plugin.onKeyUp { event -> heldGroupKeys.remove(event.eventValue) }
        }
        overlay.position.inTL(0f, 0f)
    }

    /** The picker row whose on-screen box contains (x, y), or null. */
    private fun rowUnderCursor(picker: UIPanelAPI, x: Float, y: Float): Any? {
        val table = findTable(picker) ?: return null
        val rows = table.invoke("getRows") as? List<*> ?: return null
        return rows.firstOrNull { it != null && hitTest(it, x, y) }
    }

    /** Maps a number-row key code to a custom-group index (1..[HullmodPrefs.GROUP_COUNT], with 0 ->
     *  the last group), or null for any other key. */
    private fun groupKeyToIndex(keyCode: Int): Int? {
        val i = groupKeys.indexOf(keyCode)
        return if (i >= 0) i + 1 else null
    }

    // --- Left filter column --------------------------------------------------------------------

    private fun injectLeftPanel(picker: UIPanelAPI) {
        // Fill the free zone (the greyed-out ship selector) from a small margin up to the list.
        val tableComp = findTable(picker) as? UIComponentAPI ?: return
        val gap = 10f
        val rightScreen = tableComp.left - gap
        val w = ((rightScreen - 20f) * 0.75f).coerceAtLeast(160f)
        val leftScreen = rightScreen - w

        val base = Misc.getBasePlayerColor()
        val bg = Misc.getDarkPlayerColor()
        val bright = Misc.getBrightPlayerColor()
        val pad = 14f
        val innerW = w - 2f * pad

        leftPanel = picker.CustomPanel(w, picker.height) { plugin ->
            plugin.renderBelow { alpha ->
                GL11.glColor4f(0f, 0f, 0f, 0.55f * alpha)
                GL11.glRectf(plugin.left, plugin.bottom, plugin.right, plugin.top)
                GL11.glColor4f(0.5f, 0.8f, 1f, alpha)
                drawBorder(plugin.left, plugin.top, plugin.right, plugin.bottom)
            }
            // Swallow clicks that land on our panel so the vanilla picker doesn't treat them as a
            // "click outside" and close itself (issue: clicking panel background closed the dialog).
            // The panel's own child widgets process their clicks first; this only stops the leftover
            // from reaching the dialog underneath.
            plugin.onClick { event -> if (event.isLMBDownEvent) event.consume() }

            Text("Filters") { anchorInTopMiddleOfParent(10f) }

            Text("Search (name / design type)") { position.inTL(pad, 34f) }
            searchField = TextField(innerW, 26f, Font.VICTOR_14) {
                position.inTL(pad, 54f)
                text = FilterState.searchText
            }

            AreaCheckbox("Favourites only", base, bg, bright, innerW, 28f, leftAlign = true) {
                position.inTL(pad, 90f)
                isChecked = FilterState.favouritesOnly
                onClick { FilterState.favouritesOnly = isChecked }
            }
            AreaCheckbox("Show blacklisted", base, bg, bright, innerW, 28f, leftAlign = true) {
                position.inTL(pad, 122f)
                isChecked = FilterState.showBlacklisted
                onClick { FilterState.showBlacklisted = isChecked }
            }
            AreaCheckbox("Applicable only", base, bg, bright, innerW, 28f, leftAlign = true) {
                position.inTL(pad, 154f)
                isChecked = FilterState.applicableOnly
                onClick { FilterState.applicableOnly = isChecked }
            }

            Button("Reset filters", bright, bg, width = innerW, height = 24f) {
                position.inTL(pad, 190f)
                onClick { resetFilters(picker) }
            }

            // Fixed legend pinned to the bottom so it stays out of the way.
            val legendH = 128f
            TooltipMakerPanel(innerW, legendH) {
                setParaFontColor(Misc.getGrayColor())
                addPara(legendText(), 2f)
            }.position.inBL(pad, 10f)

            // Scrollable facet column between the toggles and the legend. The nested scroller never
            // gets the mouse wheel routed to it by the engine (the dialog grabs it), so we drive it
            // ourselves: capture scroll events on the container's plugin and move the scroller's
            // yOffset. The scroller still clips its content to the viewport for us.
            val facetTop = 222f
            val facetH = (picker.height - facetTop - (legendH + 18f)).coerceAtLeast(140f)
            val facetBox = CustomPanel(innerW, facetH) { fcPlugin ->
                val rowWidth = innerW - 26f
                // Build the scroller the way LunaLib does: create the element, add ALL content, and
                // only THEN addUIElement -- the engine reads the content height at add-time to set the
                // scroll range. (The framework's TooltipMakerPanel adds first and builds after, which
                // leaves the range at 0, so any yOffset > 0 just blanked the list.)
                val tm = createUIElement(innerW, facetH, true)
                tm.position.inTL(0f, 0f)
                // CUSTOM GROUPS first (the 8 RTS-style squares), then TYPE (people filter by type more
                // than design), then DESIGN TYPE. Grids are laid out manually to save vertical space, so
                // we set heightSoFar by hand for the scroller's range.
                var y = 4f
                y = groupRow(tm, FilterState.selectedGroups, base, bg, bright, rowWidth, y)
                y += 12f
                y = facetGroup(tm, "TYPE", facetModel.types,
                    FilterState.selectedTypes, base, bg, bright, rowWidth, y, columns = 2)
                y += 12f
                y = facetGroup(tm, "DESIGN TYPE", facetModel.designTypes,
                    FilterState.selectedDesignTypes, base, bg, bright, rowWidth, y, columns = 1)
                tm.setHeightSoFar(y + 8f)
                addUIElement(tm)

                // Range is correct now (content added before addUIElement), so setYOffset sticks --
                // no need to re-assert every frame. Re-asserting would actually fight the native
                // scrollbar drag and pin the list in place. Just nudge yOffset on the scroll event
                // (which does reach the plugin) and let the native scrollbar handle dragging.
                val scroller = tm.externalScroller
                fcPlugin.onScroll { event ->
                    val s = scroller ?: return@onScroll
                    val maxOffset = (tm.heightSoFar - facetH).coerceAtLeast(0f)
                    if (maxOffset <= 0f) return@onScroll
                    val dir = if (event.eventValue > 0) -1f else 1f   // wheel up -> toward top
                    s.yOffset = (s.yOffset + dir * 64f).coerceIn(0f, maxOffset)
                    event.consume()
                }
            }
            facetBox.position.inTL(pad, facetTop)
        }.apply {
            position.inTL(0f, 0f)
            xAlignOffset = leftScreen - picker.left
        }
    }

    private fun hitTest(row: Any, x: Float, y: Float): Boolean {
        val comp = row as? UIComponentAPI ?: return false
        return x in comp.left..comp.right && y in comp.bottom..comp.top
    }

    // --- Facets --------------------------------------------------------------------------------

    /** The hull-mods the picker actually offers for this ship: the player's available mods, exactly
     *  what `ModPickerDialogV3.updateTable` iterates. NOT all specs in the game (that over-counts to
     *  mods you don't own) and NOT the displayed rows (those carry the vanilla bottom-bar filter). */
    private fun availableHullMods(picker: UIPanelAPI): List<HullModSpecAPI> = runCatching {
        val plugin = picker.invoke("getRefitPanel")?.invoke("getCoreUI")?.invoke("getPlugin")
        (plugin?.invoke("getAvailableHullMods") as? List<*>)?.filterIsInstance<HullModSpecAPI>()
    }.getOrNull() ?: emptyList()

    /** Build the design-type + type facet lists with counts, over the picker's available-to-this-ship set. */
    private fun buildFacetModel(picker: UIPanelAPI) {
        val ship = resolveShip(picker)
        val specs = availableHullMods(picker)
            .filter { !it.isHidden }
            .filter { ship == null || isApplicableToShip(picker, ship, it, it.id) }
        val design = specs.groupingBy { designTypeOf(it) }.eachCount().toList().sortedBy { it.first.lowercase() }
        val types = specs.flatMap { it.uiTags }.groupingBy { it }.eachCount().toList().sortedBy { it.first.lowercase() }
        facetModel = FacetModel(design, types)
    }

    /**
     * Renders the row of custom-group toggle squares (1..[HullmodPrefs.GROUP_COUNT], labelled 1-9
     * then 0) into the scrollable tooltip-maker. Selection mirrors the facets: plain click selects
     * ONLY that group, clicking the sole-selected one clears it, and Shift/Ctrl+click toggles a group
     * in/out -- so selected groups show their union (OR), AND-ed with the other filters. Adding a mod
     * TO a group is done by hovering it and pressing the matching number key (see the marking
     * overlay); these squares are filter/display only. Returns the y just past the row.
     */
    private fun groupRow(
        tm: TooltipMakerAPI, selected: MutableSet<Int>,
        base: Color, bg: Color, bright: Color, rowWidth: Float, startY: Float,
    ): Float {
        var y = startY
        tm.addSectionHeading("CUSTOM GROUPS", base, bg, Alignment.MID, 0f).position.inTL(0f, y)
        y += 24f
        val n = HullmodPrefs.GROUP_COUNT
        val gap = 3f
        val sq = ((rowWidth - gap * (n - 1)) / n).coerceIn(10f, 28f)
        val boxes = ArrayList<Pair<Int, ButtonAPI>>(n)
        for (i in 1..n) {
            val label = (i % 10).toString()      // 1..9 then 0 for the last group
            val cb = tm.AreaCheckbox(label, base, bg, bright, sq, sq, font = Font.VICTOR_10, leftAlign = false) {
                position.inTL((i - 1) * (sq + gap), y)
            }
            cb.isChecked = i in selected
            cb.onClick {
                if (isMultiSelectKeyDown()) {
                    if (!selected.add(i)) selected.remove(i)         // toggle membership
                } else {
                    val wasSole = selected.size == 1 && i in selected
                    selected.clear()
                    if (!wasSole) selected.add(i)                    // exclusive, or clear if re-clicked
                }
                boxes.forEach { (g, b) -> b.isChecked = g in selected }
            }
            boxes.add(i to cb)
        }
        return y + sq
    }

    /**
     * Renders one multi-select facet group into a (scrollable) tooltip-maker, auto-stacked.
     * Selection model (Windows-folder style): plain click selects ONLY that entry; clicking the
     * sole-selected entry clears the group (= no filter); Shift/Ctrl+click toggles an entry in/out.
     * An empty selection means "no filter" for that group.
     */
    private fun facetGroup(
        tm: TooltipMakerAPI, title: String, entries: List<Pair<String, Int>>,
        selected: MutableSet<String>, base: Color, bg: Color, bright: Color,
        rowWidth: Float, startY: Float, columns: Int,
    ): Float {
        var y = startY
        tm.addSectionHeading(title, base, bg, Alignment.MID, 0f).position.inTL(0f, y)
        y += 24f
        if (entries.isEmpty()) {
            tm.addPara("(none)", 0f).position.inTL(2f, y)
            return y + 20f
        }
        // Lay out in `columns` columns (1 = full width, for long names like design types).
        val colGap = 6f
        val colW = (rowWidth - colGap * (columns - 1)) / columns
        val rowH = 24f
        val rowsTop = y
        val boxes = ArrayList<Pair<String, ButtonAPI>>(entries.size)
        entries.forEachIndexed { i, (name, count) ->
            val col = i % columns
            val row = i / columns
            val cb = tm.AreaCheckbox("$name ($count)", base, bg, bright, colW, 20f, leftAlign = true) {
                position.inTL(col * (colW + colGap), rowsTop + row * rowH)
            }
            cb.isChecked = name in selected
            cb.onClick {
                if (isMultiSelectKeyDown()) {
                    if (!selected.add(name)) selected.remove(name)   // toggle membership
                } else {
                    val wasSole = selected.size == 1 && name in selected
                    selected.clear()
                    if (!wasSole) selected.add(name)                 // exclusive, or clear if re-clicked
                }
                boxes.forEach { (n, b) -> b.isChecked = n in selected }
            }
            boxes.add(name to cb)
        }
        val rows = (entries.size + columns - 1) / columns
        return rowsTop + rows * rowH
    }

    private fun isMultiSelectKeyDown(): Boolean =
        Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT) ||
            Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)

    private fun legendText(): String =
        "Ctrl+click a mod = blacklist it\n" +
            "Shift+click a mod = favourite it\n" +
            "Hover a mod, press 1-0 = custom group\n" +
            "Hold  `  = show favourites only\n" +
            "Hold Alt = reveal blacklisted\n" +
            "Shift+click = add to filter selection"

    // --- Helpers -------------------------------------------------------------------------------

    /** The picker's `UITable` (found by the table-unique `getRowForData` method). */
    private fun findTable(picker: UIPanelAPI): Any? =
        findDescendant(picker) { it.hasMethod("getRowForData") }

    /**
     * Removes the vanilla design-type filter bar at the bottom of the picker (our left panel
     * replaces it). The filter widget is found by its unique `getAllTags` method; we keep a
     * reference ([vanillaTags]) to keep it fully checked so it never trims our list, then drop its
     * scroller container (`setMaxShadowHeight`) from the layout.
     */
    private fun hideVanillaFilterBar(picker: UIPanelAPI) {
        val tagsWidget = findDescendant(picker) { it.hasMethod("getAllTags") } as? UIComponentAPI ?: return
        vanillaTags = tagsWidget
        runCatching { tagsWidget.invoke("checkAll") }
        var node: UIComponentAPI? = tagsWidget
        while (node != null && !node.hasMethod("setMaxShadowHeight")) node = node.parent
        val bar = node ?: tagsWidget.parent ?: tagsWidget
        val barBottom = bar.bottom
        runCatching { bar.parent?.removeComponent(bar) }

        // Reclaim the freed space: stretch the mod list down to where the bar's bottom was, keeping
        // its top edge in place. autoSizeToHeight reflows the rows into the taller viewport.
        val table = findTable(picker) as? UIComponentAPI ?: return
        val top = table.top
        val newBottom = barBottom + 30f          // leave a little breathing room at the dialog's bottom
        val newHeight = top - newBottom
        if (newHeight > table.height + 2f) runCatching {
            table.invoke("autoSizeToHeight", newHeight)
            table.position.setLocation(table.left, newBottom)
        }
    }

    /** The combat Ship being refitted: picker -> refit panel -> ship display -> ship (implements ShipAPI). */
    private fun resolveShip(picker: UIPanelAPI): Any? = runCatching {
        val refitPanel = picker.invoke("getRefitPanel")
        val shipDisplay = refitPanel?.invoke("getShipDisplay")
        shipDisplay?.invoke("getShip")
    }.getOrNull()

    /** Order-independent fingerprint of the non-built-in mods installed on the ship, or "" if there is
     *  no ship (i.e. not filtering by applicability). Used to detect a loadout edit that can change
     *  what's applicable. */
    private fun installedModsFingerprint(ship: Any?): String {
        if (ship == null) return ""
        return runCatching {
            val mods = ship.invoke("getVariant")?.invoke("getNonBuiltInHullmods") as? Collection<*>
            mods?.map { it.toString() }?.sorted()?.joinToString(",") ?: ""
        }.getOrNull() ?: ""
    }

    /** Uses the dialog's own check (the one that renders rows as "n/a"); cached per picker session. */
    private fun isApplicableToShip(picker: UIPanelAPI, ship: Any, data: Any, id: String): Boolean =
        applicableCache.getOrPut(id) {
            runCatching { picker.invoke("isApplicable", data, ship) as? Boolean }.getOrNull() ?: true
        }

    /** Case-insensitive substring match on the hull-mod name and its design type (manufacturer). */
    private fun matchesSearch(spec: HullModSpecAPI, queryLower: String): Boolean {
        val name = spec.displayName?.lowercase() ?: ""
        val manufacturer = spec.manufacturer?.lowercase() ?: ""
        return name.contains(queryLower) || manufacturer.contains(queryLower)
    }

    private fun designTypeOf(spec: HullModSpecAPI): String =
        spec.manufacturer?.takeIf { it.isNotBlank() } ?: "Unknown"

    private fun Any.specOrNull(): HullModSpecAPI? = runCatching { invoke("getData") }.getOrNull() as? HullModSpecAPI
}
