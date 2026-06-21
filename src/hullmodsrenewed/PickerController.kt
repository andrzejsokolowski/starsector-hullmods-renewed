package hullmodsrenewed

import com.fs.starfarer.api.loading.HullModSpecAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import hullmodsrenewed.RefitPickerInjector.Companion.findDescendant
import hullmodsrenewed.RefitPickerInjector.Companion.hasMethod
import org.lwjgl.input.Keyboard
import org.starficz.UIFramework.CustomPanel
import org.starficz.UIFramework.ReflectionUtils.invoke
import org.starficz.UIFramework.bottom
import org.starficz.UIFramework.height
import org.starficz.UIFramework.left
import org.starficz.UIFramework.playSound
import org.starficz.UIFramework.right
import org.starficz.UIFramework.top
import org.starficz.UIFramework.width

/**
 * Operates on an open hull-mod picker (`ModPickerDialogV3`):
 *  - **Filter:** removes blacklisted hull-mod rows from the picker's `UITable` each frame
 *    (re-applied after the dialog rebuilds its table). Holding **Alt** reveals blacklisted rows
 *    so they can be un-blacklisted.
 *  - **Mark:** a transparent overlay over the picker turns **Ctrl+click** into "toggle blacklist"
 *    and **Shift+click** into "toggle favourite" on the row under the cursor, consuming the click
 *    so the vanilla dialog doesn't also install the mod. Plain clicks pass straight through.
 *
 * v0.1 records favourites but does not yet filter by them — that's the M3 "Favourites tab".
 */
object PickerController {

    private var injectedPicker: UIPanelAPI? = null
    private var wasRevealing = false

    fun process(picker: UIPanelAPI) {
        if (picker !== injectedPicker) {
            injectedPicker = picker
            wasRevealing = false
            injectMarkingOverlay(picker)
        }
        applyBlacklistFilter(picker)
    }

    // --- Filtering -----------------------------------------------------------------------------

    private fun applyBlacklistFilter(picker: UIPanelAPI) {
        val table = findTable(picker) ?: return

        val revealing = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)
        if (revealing && !wasRevealing) {
            // Entering reveal mode: ask the dialog to repopulate so hidden rows come back.
            runCatching { picker.invoke("updateTable") }
        }
        wasRevealing = revealing
        if (revealing) return

        val blacklist = HullmodPrefs.blacklist()
        if (blacklist.isEmpty()) return

        val rows = table.invoke("getRows") as? List<*> ?: return
        val toRemove = rows.filter { row ->
            val id = row?.specOrNull()?.id
            id != null && id in blacklist
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
