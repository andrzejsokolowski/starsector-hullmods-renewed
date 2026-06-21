package hullmodsrenewed;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * Entry point for Hullmods - Renewed.
 *
 * <p>Currently a stub: it just confirms the mod loads. The refit hull-mod picker overhaul
 * (blacklist / favourites / templates) will be wired up here once the design is locked in —
 * most likely by registering an {@code EveryFrameScript} that injects custom UI into the
 * refit screen, following the reflection-based approach proven by Refit Filters.</p>
 */
public class HullmodsRenewedModPlugin extends BaseModPlugin {

    public static final String MOD_ID = "hullmods_renewed";

    @Override
    public void onApplicationLoad() throws Exception {
        Global.getLogger(HullmodsRenewedModPlugin.class)
                .info("Hullmods - Renewed: application loaded.");
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // Transient: not saved with the campaign, so it's re-added cleanly on every load.
        Global.getSector().addTransientScript(new RefitPickerInjector());
    }
}
