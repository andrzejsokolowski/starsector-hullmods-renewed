package hullmodsrenewed

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CoreUITabId
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.campaign.CampaignState
import com.fs.state.AppDriver
import hullmodsrenewed.uiframework.ReflectionUtils.getMethodsMatching
import hullmodsrenewed.uiframework.ReflectionUtils.invoke
import hullmodsrenewed.uiframework.getChildrenCopy

/**
 * Watches the campaign refit screen and, when the hull-mod picker (`ModPickerDialogV3`) is open,
 * hands it to [PickerController] for filtering + marking.
 *
 * Mirrors the proven structure of Refit Filters' campaign UI script: run while paused, only on the
 * REFIT tab, grab the core UI (handling the docked-at-colony encounter-dialog case), then locate the
 * picker by a stable, picker-unique method name (`getAddedPermaMods`) rather than its obfuscated
 * class name. Combat/mission refit is out of scope for v0.1.
 */
class RefitPickerInjector : EveryFrameScript {

    override fun isDone(): Boolean = false
    override fun runWhilePaused(): Boolean = true

    override fun advance(amount: Float) {
        val sector = Global.getSector() ?: return
        if (!sector.isPaused) return
        if (sector.campaignUI.currentCoreTab != CoreUITabId.REFIT) return

        val state = AppDriver.getInstance().currentState
        if (state !is CampaignState) return

        // When refit is opened while docked (colony/station), the core UI lives inside the dialog.
        val dialog = state.invoke("getEncounterDialog")
        val core = (if (dialog != null) dialog.invoke("getCoreUI") else state.invoke("getCore")) as? UIPanelAPI
            ?: return

        val picker = findDescendant(core) { it.hasMethod(MARKER_METHOD) } as? UIPanelAPI ?: return
        PickerController.process(picker)
    }

    companion object {
        /** Unique to ModPickerDialogV3 among refit UI panels; survives obfuscation. */
        private const val MARKER_METHOD = "getAddedPermaMods"

        fun UIComponentAPI.hasMethod(name: String): Boolean = getMethodsMatching(name).isNotEmpty()

        /** Depth-first search of the UI tree for the first component matching [predicate]. */
        fun findDescendant(root: UIComponentAPI, predicate: (UIComponentAPI) -> Boolean): UIComponentAPI? {
            if (predicate(root)) return root
            if (root is UIPanelAPI) {
                for (child in root.getChildrenCopy()) {
                    findDescendant(child, predicate)?.let { return it }
                }
            }
            return null
        }
    }
}
