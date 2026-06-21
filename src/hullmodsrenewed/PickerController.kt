package hullmodsrenewed

import com.fs.starfarer.api.loading.HullModSpecAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import hullmodsrenewed.RefitPickerInjector.Companion.findDescendant
import hullmodsrenewed.RefitPickerInjector.Companion.hasMethod
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import hullmodsrenewed.uiframework.AreaCheckbox
import hullmodsrenewed.uiframework.CustomPanel
import hullmodsrenewed.uiframework.ReflectionUtils.invoke
import hullmodsrenewed.uiframework.Text
import hullmodsrenewed.uiframework.anchorInTopMiddleOfParent
import hullmodsrenewed.uiframework.bottom
import hullmodsrenewed.uiframework.drawBorder
import hullmodsrenewed.uiframework.height
import hullmodsrenewed.uiframework.left
import hullmodsrenewed.uiframework.onClick
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
 *  - **Mark:** a transparent overlay over the picker turns **Ctrl+click** into "toggle blacklist"
 *    and **Shift+click** into "toggle favourite" on the row under the cursor, consuming the click
 *    so the vanilla dialog doesn't also install the mod. Plain clicks pass straight through.
 */
object PickerController {

    private var injectedPicker: UIPanelAPI? = null
    private var lastSignature = ""

    fun process(picker: UIPanelAPI) {
        if (picker !== injectedPicker) {
            injectedPicker = picker
            lastSignature = ""
            RefitDebug.dumpTree(picker, "ModPickerDialogV3")
            injectMarkingOverlay(picker)
            injectLeftPanel(picker)
        }
        applyFilter(picker)
    }

    // --- Filtering -----------------------------------------------------------------------------

    private fun applyFilter(picker: UIPanelAPI) {
        val table = findTable(picker) ?: return

        // Effective = persistent toggle (button) OR momentary hold-key.
        val favOnly = FilterState.favouritesOnly ||
            Keyboard.isKeyDown(Keyboard.KEY_GRAVE)
        val showBlacklisted = FilterState.showBlacklisted ||
            Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)

        val blacklist = HullmodPrefs.blacklist()
        val favourites = HullmodPrefs.favourites()

        // When the effective filter changes, rebuild the table first so loosening brings rows back.
        val signature = "$favOnly|$showBlacklisted|${blacklist.size}|${favourites.size}"
        if (signature != lastSignature) {
            runCatching { picker.invoke("updateTable") }
            lastSignature = signature
        }

        val rows = table.invoke("getRows") as? List<*> ?: return
        val toRemove = rows.filter { row ->
            val id = row?.specOrNull()?.id ?: return@filter false
            (!showBlacklisted && id in blacklist) || (favOnly && id !in favourites)
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
        // Fill the free zone from a small screen margin up to just left of the hull-mod list.
        val tableComp = findTable(picker) as? UIComponentAPI ?: return
        val gap = 10f
        val rightScreen = tableComp.left - gap
        // 75% of the full free width, anchored to the list side (don't run to the screen edge).
        val w = ((rightScreen - 20f) * 0.75f).coerceAtLeast(140f)
        val leftScreen = rightScreen - w

        picker.CustomPanel(w, picker.height) { plugin ->
            plugin.renderBelow { alpha ->
                GL11.glColor4f(0f, 0f, 0f, 0.55f * alpha)
                GL11.glRectf(plugin.left, plugin.bottom, plugin.right, plugin.top)
                GL11.glColor4f(0.5f, 0.8f, 1f, alpha)
                drawBorder(plugin.left, plugin.top, plugin.right, plugin.bottom)
            }
            Text("Filters (WIP)") { anchorInTopMiddleOfParent(12f) }

            val base = Misc.getBasePlayerColor()
            val bg = Misc.getDarkPlayerColor()
            val bright = Misc.getBrightPlayerColor()
            val cbWidth = w - 28f

            AreaCheckbox("Favourites only", base, bg, bright, cbWidth, 30f, leftAlign = true) {
                position.inTL(14f, 48f)
                isChecked = FilterState.favouritesOnly
                onClick { FilterState.favouritesOnly = isChecked }
            }
            AreaCheckbox("Show blacklisted", base, bg, bright, cbWidth, 30f, leftAlign = true) {
                position.inTL(14f, 84f)
                isChecked = FilterState.showBlacklisted
                onClick { FilterState.showBlacklisted = isChecked }
            }
        }.apply {
            position.inTL(0f, 0f)
            xAlignOffset = leftScreen - picker.left
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
