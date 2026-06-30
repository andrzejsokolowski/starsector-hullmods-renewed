package hullmodsrenewed

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.loading.HullModSpecAPI
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.CutStyle
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
import hullmodsrenewed.uiframework.Tooltip
import hullmodsrenewed.uiframework.bottom
import hullmodsrenewed.uiframework.centerY
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

    /** Width of the marker gutter drawn to the left of the mod list (assignment column: trash =
     *  blacklisted, star = favourite, digits = custom-group membership). Reserved out of the left
     *  panel's footprint so it sits in the empty strip between the panel and the vanilla table. */
    private const val MARKER_GUTTER = 46f

    private const val STAR_SPRITE = "graphics/hullmodsrenewed/icon_star.png"
    private const val TRASH_SPRITE = "graphics/hullmodsrenewed/icon_trash.png"

    private val MARKER_STAR: Color = Misc.getHighlightColor()        // favourite = yellow star
    private val MARKER_TRASH: Color = Color(235, 90, 90)             // blacklist = red trash
    private val MARKER_NUM: Color = Misc.getBrightPlayerColor()      // custom-group digits

    /** Lazily-loaded (GL-thread) marker assets: the status icons plus a sprite per digit 0-9 (this API
     *  build has no LazyFont, so the group numbers are drawn as tintable glyph sprites too). */
    private var spritesLoaded = false
    private var starSprite: SpriteAPI? = null
    private var trashSprite: SpriteAPI? = null
    private val digitSprites = arrayOfNulls<SpriteAPI>(10)

    /** The open "rename custom group" modal (null = closed), the group it currently edits, and its
     *  text field. Lives over the picker; while it's up the marking overlay ignores clicks/number keys
     *  so typing a name doesn't also toggle group membership. */
    private var renameModal: CustomPanelAPI? = null
    private var renameGroup = 1
    private var renameField: TextFieldAPI? = null

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
            renameModal = null               // belonged to the previous picker instance; it's gone now
            renameField = null
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
        val applicabilityFp = installedModsFingerprint(picker, ship)
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
            // The assignment column: small markers in the left gutter showing each row's blacklist /
            // favourite / custom-group status. Drawn here (not as child widgets) so they clip to the
            // list viewport and track scrolling.
            plugin.render { alpha -> runCatching { drawRowMarkers(picker, alpha) } }

            // Remember where the cursor is so a number-key press knows which row it is over.
            plugin.onHover { event -> hoverX = event.x.toFloat(); hoverY = event.y.toFloat() }

            plugin.onClick { event ->
                if (renameModal != null) return@onClick   // modal owns input while it's open
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
                if (renameModal != null) return@onKeyDown // modal owns input (incl. digits) while open
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

    // --- Assignment marker column --------------------------------------------------------------

    /** Loads the marker sprites (status icons + the ten digit glyphs) once, on the GL thread. */
    private fun ensureMarkerAssets() {
        if (spritesLoaded) return
        spritesLoaded = true
        fun load(path: String): SpriteAPI? = runCatching {
            Global.getSettings().loadTexture(path)
            Global.getSettings().getSprite(path)
        }.getOrNull()
        starSprite = load(STAR_SPRITE)
        trashSprite = load(TRASH_SPRITE)
        for (d in 0..9) digitSprites[d] = load("graphics/hullmodsrenewed/digit_$d.png")
    }

    private fun drawSprite(sprite: SpriteAPI?, x: Float, y: Float, w: Float, h: Float, color: Color, alpha: Float) {
        val s = sprite ?: return
        s.setSize(w, h)
        s.color = color
        s.alphaMult = alpha
        s.render(x, y)
    }

    /**
     * Draws the per-row assignment column in the gutter left of the list: a red trash icon for a
     * blacklisted mod, a yellow star for a favourite, and the custom-group digits the mod belongs to.
     * Icons sit on the upper half of the row, group digits on the lower half (or centred when a row
     * has only one of the two). Rows scrolled out of the list's viewport are culled so nothing draws
     * over the header or past the bottom edge.
     */
    private fun drawRowMarkers(picker: UIPanelAPI, alpha: Float) {
        val table = findTable(picker) as? UIComponentAPI ?: return
        val rows = table.invoke("getRows") as? List<*> ?: return
        if (rows.isEmpty()) return

        ensureMarkerAssets()

        val vTop = table.top
        val vBottom = table.bottom
        // Right-align the markers to hug the left edge of the list, so they read as that row's column
        // rather than floating in the middle of the gutter.
        val colRight = table.left - 6f

        val blacklist = HullmodPrefs.blacklist()
        val favourites = HullmodPrefs.favourites()
        val groupSets = (1..HullmodPrefs.GROUP_COUNT).map { HullmodPrefs.groupMembers(it) }

        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glColor4f(1f, 1f, 1f, 1f)

        val iconSize = 13f
        val iconGap = 3f
        val dw = 8f      // digit glyph width
        val dh = 12f     // digit glyph height
        val dGap = 1f
        for (row in rows) {
            val comp = row as? UIComponentAPI ?: continue
            val cy = comp.centerY
            if (cy < vBottom + 6f || cy > vTop - 6f) continue   // outside the list viewport

            val spec = comp.specOrNull() ?: continue
            val id = spec.id

            val black = id in blacklist
            val fav = id in favourites
            val groups = ArrayList<Int>(2)
            for (gi in 1..HullmodPrefs.GROUP_COUNT) if (id in groupSets[gi - 1]) groups.add(gi % 10)
            if (!black && !fav && groups.isEmpty()) continue

            val iconCount = (if (black) 1 else 0) + (if (fav) 1 else 0)
            val twoRows = iconCount > 0 && groups.isNotEmpty()

            // Icons row (right-aligned), on the upper half when there's also a digit row.
            if (iconCount > 0) {
                val iconsW = iconCount * iconSize + (iconCount - 1) * iconGap
                val iconBottom = if (twoRows) cy + 1f else cy - iconSize / 2f
                var ix = colRight - iconsW
                if (black) { drawSprite(trashSprite, ix, iconBottom, iconSize, iconSize, MARKER_TRASH, alpha); ix += iconSize + iconGap }
                if (fav) drawSprite(starSprite, ix, iconBottom, iconSize, iconSize, MARKER_STAR, alpha)
            }

            // Digits row (right-aligned), on the lower half when there's also an icon row.
            if (groups.isNotEmpty()) {
                val digitsW = groups.size * dw + (groups.size - 1) * dGap
                val digitBottom = if (twoRows) cy - dh - 1f else cy - dh / 2f
                var dx = colRight - digitsW
                for (d in groups) {
                    drawSprite(digitSprites[d], dx, digitBottom, dw, dh, MARKER_NUM, alpha)
                    dx += dw + dGap
                }
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f)
    }

    // --- Rename-group modal --------------------------------------------------------------------

    private fun closeRenameModal(picker: UIPanelAPI) {
        renameModal?.let { runCatching { picker.removeComponent(it) } }
        renameModal = null
        renameField = null
    }

    private fun saveRename(picker: UIPanelAPI) {
        HullmodPrefs.setGroupName(renameGroup, renameField?.text ?: "")
        runCatching { playSound("ui_button_pressed") }
        closeRenameModal(picker)
        // Rebuild the left panel so the heading + square tooltips pick up the new name immediately.
        leftPanel?.let { runCatching { picker.removeComponent(it) } }
        injectLeftPanel(picker)
    }

    /**
     * Opens the modal-style overlay for naming custom groups: a dimmed full-screen catcher with a
     * centred box holding a single-select row of the ten group squares, a text field pre-filled with
     * the selected group's current name, and Save / Cancel (Enter saves, Esc cancels). Picking a
     * different square switches which group the field edits. Names are stored per-save in [HullmodPrefs].
     */
    private fun openRenameModal(picker: UIPanelAPI) {
        closeRenameModal(picker)
        renameGroup = FilterState.selectedGroups.minOrNull() ?: 1

        val base = Misc.getBasePlayerColor()
        val bg = Misc.getDarkPlayerColor()
        val bright = Misc.getBrightPlayerColor()

        val mw = 380f
        val mh = 210f
        val boxLeft = (picker.width - mw) / 2f
        val boxTop = (picker.height - mh) / 2f      // distance down from the panel's top edge
        val pad = 16f

        val squares = ArrayList<Pair<Int, ButtonAPI>>(HullmodPrefs.GROUP_COUNT)

        renameModal = picker.CustomPanel(picker.width, picker.height) { plugin ->
            plugin.renderBelow { a ->
                GL11.glColor4f(0f, 0f, 0f, 0.62f * a)                 // dim the screen behind us
                GL11.glRectf(plugin.left, plugin.bottom, plugin.right, plugin.top)
                val l = plugin.left + boxLeft
                val r = l + mw
                val t = plugin.top - boxTop
                val b = t - mh
                GL11.glColor4f(0f, 0f, 0f, 0.95f * a)                 // opaque box
                GL11.glRectf(l, b, r, t)
                GL11.glColor4f(0.5f, 0.8f, 1f, a)
                drawBorder(l, t, r, b)
            }
            // Swallow leftover clicks so the picker doesn't treat them as "click outside" and close.
            plugin.onClick { e -> if (e.isLMBDownEvent) e.consume() }
            plugin.onKeyDown { e ->
                when (e.eventValue) {
                    Keyboard.KEY_RETURN -> saveRename(picker)
                    Keyboard.KEY_ESCAPE -> { closeRenameModal(picker); e.consume() }
                }
            }

            val title = Text("Name custom group ${renameGroup % 10}") {
                position.inTL(boxLeft + pad, boxTop + pad)
            }

            val n = HullmodPrefs.GROUP_COUNT
            val gap = 4f
            val sq = ((mw - 2f * pad - gap * (n - 1)) / n).coerceIn(12f, 30f)
            val sqTop = boxTop + pad + 28f
            for (i in 1..n) {
                val cb = AreaCheckbox((i % 10).toString(), base, bg, bright, sq, sq, font = Font.VICTOR_10) {
                    position.inTL(boxLeft + pad + (i - 1) * (sq + gap), sqTop)
                }
                cb.isChecked = i == renameGroup
                cb.onClick {
                    renameGroup = i
                    squares.forEach { (g, b) -> b.isChecked = g == renameGroup }
                    renameField?.text = HullmodPrefs.groupName(i)
                    title.text = "Name custom group ${i % 10}"
                }
                squares.add(i to cb)
            }

            Text("Name (leave blank to clear)") { position.inTL(boxLeft + pad, sqTop + sq + 10f) }
            renameField = TextField(mw - 2f * pad, 28f, Font.VICTOR_14) {
                position.inTL(boxLeft + pad, sqTop + sq + 30f)
                text = HullmodPrefs.groupName(renameGroup)
            }

            val btnW = (mw - 2f * pad - 10f) / 2f
            val btnY = boxTop + mh - pad - 26f
            Button("Save", bright, bg, width = btnW, height = 26f) {
                position.inTL(boxLeft + pad, btnY)
                onClick { saveRename(picker) }
            }
            Button("Cancel", base, bg, width = btnW, height = 26f) {
                position.inTL(boxLeft + pad + btnW + 10f, btnY)
                onClick { closeRenameModal(picker) }
            }
        }.apply { position.inTL(0f, 0f) }
    }

    // --- Left filter column --------------------------------------------------------------------

    private fun injectLeftPanel(picker: UIPanelAPI) {
        // Fill the free zone (the greyed-out ship selector) from a small margin up to the list, leaving
        // the marker gutter (the assignment column drawn by drawRowMarkers) free between us and the list.
        val tableComp = findTable(picker) as? UIComponentAPI ?: return
        val gap = 10f
        val rightScreen = tableComp.left - MARKER_GUTTER - gap
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
            val legendH = 146f
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
                y += 4f
                tm.setButtonFontVictor10()
                val renameBtn = tm.addButton("Name groups…", null, base, bg,
                    Alignment.MID, CutStyle.TL_BR, rowWidth, 18f, 0f)
                renameBtn.position.inTL(0f, y)
                renameBtn.onClick { openRenameModal(picker) }
                y += 22f
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
        // Heading shows the active group's name when exactly one is selected, else the generic title.
        val heading = if (selected.size == 1) HullmodPrefs.groupLabel(selected.first()).uppercase()
        else "CUSTOM GROUPS"
        tm.addSectionHeading(heading, base, bg, Alignment.MID, 0f).position.inTL(0f, y)
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
            // Hover tooltip naming the group (and its size), so you can recall what's inside it.
            (cb as UIComponentAPI).Tooltip(TooltipMakerAPI.TooltipLocation.ABOVE, 220f) {
                val members = HullmodPrefs.groupMembers(i).size
                setParaFontColor(bright)
                addPara(HullmodPrefs.groupLabel(i), 0f)
                setParaFontColor(base)
                addPara(if (members == 1) "1 hull-mod" else "$members hull-mods", 4f)
                setParaFontColor(Misc.getGrayColor())
                addPara("Hover a mod in the list, press ${i % 10} to add/remove it here.", 6f)
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
            "Left column: trash=blacklist, star=favourite, #=groups\n" +
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

    /** Order-independent fingerprint of the non-built-in mods installed on the ship being refitted, or
     *  "" when not filtering by applicability. Used to detect a loadout edit (install OR remove) that
     *  can change what's applicable. Reads the live edited variant via the refit panel's
     *  `getCurrentVariant` -- the ship's own `getVariant()` is a snapshot that does not reflect removals. */
    private fun installedModsFingerprint(picker: UIPanelAPI, ship: Any?): String {
        if (ship == null) return ""
        return runCatching {
            val variant = picker.invoke("getRefitPanel")?.invoke("getShipDisplay")?.invoke("getCurrentVariant")
            val mods = variant?.invoke("getNonBuiltInHullmods") as? Collection<*>
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
