package hullmodsrenewed

import com.fs.starfarer.api.loading.HullModSpecAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import hullmodsrenewed.RefitPickerInjector.Companion.findDescendant
import hullmodsrenewed.RefitPickerInjector.Companion.hasMethod
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import hullmodsrenewed.uiframework.CustomPanel
import hullmodsrenewed.uiframework.ReflectionUtils.invoke
import hullmodsrenewed.uiframework.Text
import hullmodsrenewed.uiframework.anchorInTopMiddleOfParent
import hullmodsrenewed.uiframework.bottom
import hullmodsrenewed.uiframework.drawBorder
import hullmodsrenewed.uiframework.height
import hullmodsrenewed.uiframework.left
import hullmodsrenewed.uiframework.playSound
import hullmodsrenewed.uiframework.right
import hullmodsrenewed.uiframework.top
import hullmodsrenewed.uiframework.width
import hullmodsrenewed.uiframework.xAlignOffset

/**
 * Operates on an open hull-mod picker (`ModPickerDialogV3`):
 *  - **Filter:** removes rows from the picker's `UITable` each frame (re-applied after the dialog
 *    rebuilds its table). Modes, via temporary keybinds until the real filter UI exists:
 *      - default: hide blacklisted mods
 *      - hold **Alt**: reveal everything (so blacklisted mods can be un-blacklisted)
 *      - hold **`** (backtick/tilde): show **favourites only**
 *  - **Mark:** a transparent overlay over the picker turns **Ctrl+click** into "toggle blacklist"
 *    and **Shift+click** into "toggle favourite" on the row under the cursor, consuming the click
 *    so the vanilla dialog doesn't also install the mod. Plain clicks pass straight through.
 */
object PickerController {

    private enum class FilterMode { NORMAL, REVEAL, FAVOURITES_ONLY }

    private var injectedPicker: UIPanelAPI? = null
    private var lastMode = FilterMode.NORMAL

    fun process(picker: UIPanelAPI) {
        if (picker !== injectedPicker) {
            injectedPicker = picker
            RefitDebug.dumpTree(picker, "ModPickerDialogV3")
            injectMarkingOverlay(picker)
            injectLeftPanel(picker)
        }
        applyFilter(picker)
    }

    // --- Filtering -----------------------------------------------------------------------------

    private fun applyFilter(picker: UIPanelAPI) {
        val table = findTable(picker) ?: return

        val alt = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)
        val favOnly = Keyboard.isKeyDown(Keyboard.KEY_GRAVE)
        val mode = when {
            alt -> FilterMode.REVEAL
            favOnly -> FilterMode.FAVOURITES_ONLY
            else -> FilterMode.NORMAL
        }

        // On any mode change, repopulate first so rows hidden under the previous mode come back
        // before we re-filter — this makes every transition (grow or shrink) behave correctly.
        if (mode != lastMode) runCatching { picker.invoke("updateTable") }
        lastMode = mode
        if (mode == FilterMode.REVEAL) return

        val blacklist = HullmodPrefs.blacklist()
        val favourites = HullmodPrefs.favourites()
        if (blacklist.isEmpty() && mode != FilterMode.FAVOURITES_ONLY) return

        val rows = table.invoke("getRows") as? List<*> ?: return
        val toRemove = rows.filter { row ->
            val id = row?.specOrNull()?.id ?: return@filter false
            id in blacklist || (mode == FilterMode.FAVOURITES_ONLY && id !in favourites)
        }
        if (toRemove.isEmpty()) return

        runCatching {
            table.invoke("suspendRecompute")
            toRemove.forEach { table.invoke("removeRow", it) }
            table.invoke("resumeRecompute")
        }
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

    // --- Left filter column (WIP: first-cut placement, to be tuned from screenshots) -----------

    private fun injectLeftPanel(picker: UIPanelAPI) {
        val panelWidth = 160f
        picker.CustomPanel(panelWidth, picker.height) { plugin ->
            plugin.renderBelow { alpha ->
                GL11.glColor4f(0f, 0f, 0f, 0.55f * alpha)
                GL11.glRectf(plugin.left, plugin.bottom, plugin.right, plugin.top)
                GL11.glColor4f(0.5f, 0.8f, 1f, alpha)
                drawBorder(plugin.left, plugin.top, plugin.right, plugin.bottom)
            }
            Text("Filters (WIP)") { anchorInTopMiddleOfParent(10f) }
        }.apply {
            // Sit just left of the picker dialog, in the (free) ship-selector space.
            position.inTL(0f, 0f)
            xAlignOffset = -(panelWidth + 8f)
        }
    }

    private fun hitTest(row: Any, x: Float, y: Float): Boolean {
        val comp = row as? UIComponentAPI ?: return false
        return x in comp.left..comp.right && y in comp.bottom..comp.top
    }

    // --- Helpers -------------------------------------------------------------------------------

    /** The picker's `UITable` (found by the table-unique `getRowForData` method). */
    private fun findTable(picker: UIPanelAPI): Any? =
        findDescendant(picker) { it.hasMethod("getRowForData") }

    private fun Any.specOrNull(): HullModSpecAPI? = runCatching { invoke("getData") }.getOrNull() as? HullModSpecAPI
}
