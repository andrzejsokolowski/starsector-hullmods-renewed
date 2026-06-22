package hullmodsrenewed

import com.fs.starfarer.api.Global
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

    // TEMP diagnostics for the sort-reset bug. Capped so it can't flood the log.
    private val log = Global.getLogger(PickerController::class.java)
    private var sortLogCount = 0
    private var prevSortId = -1

    /** Cache of "is this hull-mod applicable to the current ship" (id -> applicable); cleared on ship change. */
    private val applicableCache = HashMap<String, Boolean>()

    fun process(picker: UIPanelAPI) {
        if (picker !== injectedPicker) {
            injectedPicker = picker
            lastSignature = ""
            applicableCache.clear()
            searchField = null
            defaultSortColumn = null
            desiredSortColumn = null
            sortResetHandled = false
            sortLogCount = 0
            prevSortId = -1
            FilterState.clearTransient()   // fresh search + facet selection each time the picker opens
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
        val cid = System.identityHashCode(curSort)
        if (cid != prevSortId && sortLogCount < 40) {
            prevSortId = cid; sortLogCount++
            log.info("HMR sort track: cur=#$cid(${curSort?.javaClass?.simpleName}) " +
                "default=#${System.identityHashCode(defaultSortColumn)} desired=#${System.identityHashCode(desiredSortColumn)}")
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

        searchField?.let { FilterState.searchText = it.text?.trim() ?: "" }
        val query = FilterState.searchText.lowercase()
        val selDesign = FilterState.selectedDesignTypes
        val selType = FilterState.selectedTypes

        val blacklist = HullmodPrefs.blacklist()
        val favourites = HullmodPrefs.favourites()

        // When the effective filter changes, rebuild the table first so loosening brings rows back.
        val signature = "$favOnly|$showBlacklisted|$applicableOnly|$query|${selDesign.sorted()}|" +
            "${selType.sorted()}|${blacklist.size}|${favourites.size}|${System.identityHashCode(ship)}"
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
            if (sortLogCount < 40) {
                sortLogCount++
                log.info("HMR rebuilt: now=#${System.identityHashCode(runCatching { table.invoke("getLastSortColumn") }.getOrNull())} desired=#${System.identityHashCode(desiredSortColumn)}")
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
                    else -> false
                }
            }
            if (toRemove.isNotEmpty()) runCatching {
                table.invoke("suspendRecompute")
                toRemove.forEach { table.invoke("removeRow", it) }
                table.invoke("resumeRecompute")
            }
        }
    }

    private fun resetFilters(picker: UIPanelAPI) {
        FilterState.favouritesOnly = false
        FilterState.showBlacklisted = false
        FilterState.applicableOnly = true
        FilterState.searchText = ""
        FilterState.selectedDesignTypes.clear()
        FilterState.selectedTypes.clear()
        lastSignature = ""                       // force a table rebuild on the next frame
        leftPanel?.let { runCatching { picker.removeComponent(it) } }
        injectLeftPanel(picker)                  // rebuild so every checkbox/field shows the reset state
        runCatching { playSound("ui_button_pressed") }
    }

    // --- Marking overlay -----------------------------------------------------------------------

    private fun injectMarkingOverlay(picker: UIPanelAPI) {
        val overlay: CustomPanelAPI = picker.CustomPanel(picker.width, picker.height) { plugin ->
            plugin.onClick { event ->
                if (!event.isLMBDownEvent) return@onClick
                val ctrl = event.isCtrlDown
                val shift = event.isShiftDown
                if (!ctrl && !shift) return@onClick // let vanilla handle normal clicks

                val table = findTable(picker) ?: return@onClick
                val rows = table.invoke("getRows") as? List<*> ?: return@onClick
                val row = rows.firstOrNull { it != null && hitTest(it, event.x.toFloat(), event.y.toFloat()) }
                    ?: return@onClick
                val spec = row.specOrNull() ?: return@onClick

                if (ctrl) HullmodPrefs.toggleBlacklist(spec.id) else HullmodPrefs.toggleFavourite(spec.id)
                runCatching { playSound("ui_button_pressed") } // audible confirmation; never fatal
                event.consume()
            }
        }
        overlay.position.inTL(0f, 0f)
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
            val legendH = 110f
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
                // TYPE first (people filter by type more than design), then DESIGN TYPE. Both grids
                // are laid out manually 2-per-row to save vertical space, so we set heightSoFar by
                // hand for the scroller's range.
                var y = 4f
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
