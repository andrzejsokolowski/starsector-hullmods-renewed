package hullmodsrenewed;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * Entry point for Hullmods - Renewed.
 *
 * <p>Reads the LunaSettings config at application load (which also seeds the picker's default
 * filter state), and on every game load registers the {@code EveryFrameScript} that injects the
 * filter panel into the refit screen, plus a one-time import of any preferences the save still
 * carries from the per-save storage used up to v1.5.0.</p>
 */
public class HullmodsRenewedModPlugin extends BaseModPlugin {

    public static final String MOD_ID = "hullmods_renewed";

    @Override
    public void onApplicationLoad() throws Exception {
        HmrSettings.INSTANCE.init();
        Global.getLogger(HullmodsRenewedModPlugin.class)
                .info("Hullmods - Renewed: application loaded.");
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // Preferences live per-installation now; fold in whatever this save still holds from the
        // per-save days. No-op after the first load of a given save (and for brand-new ones).
        HullmodPrefs.INSTANCE.migrateFromSave();
        // Transient: not saved with the campaign, so it's re-added cleanly on every load.
        Global.getSector().addTransientScript(new RefitPickerInjector());
    }
}
