package hullmodsrenewed

import com.fs.starfarer.api.Global
import lunalib.lunaSettings.LunaSettings
import lunalib.lunaSettings.LunaSettingsListener
import org.lazywizard.lazylib.JSONUtils
import java.awt.Color

/**
 * Reads the mod's LunaSettings (`data/config/LunaSettings.csv`) into plain fields and keeps them in
 * sync while the game runs.
 *
 * Two kinds of setting live here:
 *  - **Default filter state** — what the picker's toggles start at, and what "Reset filters" returns
 *    them to. Changing one in the menu also applies it to the live [FilterState] right away, so you
 *    can see the effect without restarting; the other filters are left alone.
 *  - **Appearance** — the filter panel's outline colour and background opacity, read per frame by
 *    the render callbacks in [PickerController] so edits show up immediately.
 *
 * Plus one action masquerading as a setting: **Wipe saved preferences**. LunaSettings has no button
 * field type, so it's a boolean that we act on and then reset to `false` (see [handleWipeRequest]).
 *
 * Every read falls back to the hardcoded default below, so a missing field, a broken CSV, or an
 * absent LunaLib degrades to the mod's previous behaviour instead of failing.
 */
object HmrSettings {

    private const val MOD_ID = HullmodsRenewedModPlugin.MOD_ID

    private const val KEY_DEFAULT_APPLICABLE_ONLY = "hmr_default_applicable_only"
    private const val KEY_DEFAULT_FAVOURITES_ONLY = "hmr_default_favourites_only"
    private const val KEY_DEFAULT_SHOW_BLACKLISTED = "hmr_default_show_blacklisted"
    private const val KEY_DEFAULT_SEARCH_DESCRIPTIONS = "hmr_default_search_descriptions"
    private const val KEY_BORDER_COLOR = "hmr_border_color"
    private const val KEY_PANEL_OPACITY = "hmr_panel_opacity"
    private const val KEY_WIPE_PREFS = "hmr_wipe_prefs"

    /** The LunaSettings store for this mod, under `saves/common/`. */
    private const val LUNA_SETTINGS_FILE = "LunaSettings/$MOD_ID.json"

    private val log = Global.getLogger(HmrSettings::class.java)

    /** Matches the pre-settings hardcoded border, `glColor4f(0.5f, 0.8f, 1f)`. */
    private val DEFAULT_BORDER_COLOR = Color(128, 204, 255)
    private const val DEFAULT_PANEL_OPACITY = 0.55f

    // --- Live values ----------------------------------------------------------------------------

    var defaultApplicableOnly = true; private set
    var defaultFavouritesOnly = false; private set
    var defaultShowBlacklisted = false; private set
    var defaultSearchDescriptions = false; private set

    var borderColor: Color = DEFAULT_BORDER_COLOR; private set
    var panelOpacity: Float = DEFAULT_PANEL_OPACITY; private set

    // --- Wiring ---------------------------------------------------------------------------------

    /** Called once from the mod plugin at application load. Reads the settings, seeds [FilterState]
     *  from the configured defaults, and registers the change listener. */
    fun init() {
        if (!isLunaAvailable()) {
            log.warn("Hullmods - Renewed: LunaLib not enabled; using built-in defaults for every setting.")
            return
        }
        reload()
        FilterState.applyDefaults()
        // Catches a wipe requested through a path that doesn't fire the listener (LunaLib skips it
        // when its menu is opened via the console command), so the flag can never sit armed forever.
        handleWipeRequest()
        runCatching {
            if (!LunaSettings.hasSettingsListenerOfClass(Listener::class.java)) {
                LunaSettings.addSettingsListener(Listener())
            }
        }.onFailure { log.error("Hullmods - Renewed: could not register the LunaSettings listener.", it) }
    }

    private fun isLunaAvailable(): Boolean =
        runCatching { Global.getSettings().modManager.isModEnabled("lunalib") }.getOrDefault(false)

    private class Listener : LunaSettingsListener {
        override fun settingsChanged(modID: String) {
            if (modID != MOD_ID) return
            val before = snapshotDefaults()
            reload()
            // Only push a default that the player actually just changed onto the live filters, so
            // editing (say) the panel colour never resets the filters they're in the middle of using.
            applyChangedDefaults(before)
            handleWipeRequest()
        }
    }

    private data class DefaultsSnapshot(
        val applicableOnly: Boolean, val favouritesOnly: Boolean,
        val showBlacklisted: Boolean, val searchDescriptions: Boolean,
    )

    private fun snapshotDefaults() = DefaultsSnapshot(
        defaultApplicableOnly, defaultFavouritesOnly, defaultShowBlacklisted, defaultSearchDescriptions,
    )

    private fun applyChangedDefaults(before: DefaultsSnapshot) {
        if (defaultApplicableOnly != before.applicableOnly) FilterState.applicableOnly = defaultApplicableOnly
        if (defaultFavouritesOnly != before.favouritesOnly) FilterState.favouritesOnly = defaultFavouritesOnly
        if (defaultShowBlacklisted != before.showBlacklisted) FilterState.showBlacklisted = defaultShowBlacklisted
        if (defaultSearchDescriptions != before.searchDescriptions) FilterState.searchDescriptions = defaultSearchDescriptions
    }

    // --- Reading --------------------------------------------------------------------------------

    private fun reload() {
        defaultApplicableOnly = bool(KEY_DEFAULT_APPLICABLE_ONLY, true)
        defaultFavouritesOnly = bool(KEY_DEFAULT_FAVOURITES_ONLY, false)
        defaultShowBlacklisted = bool(KEY_DEFAULT_SHOW_BLACKLISTED, false)
        defaultSearchDescriptions = bool(KEY_DEFAULT_SEARCH_DESCRIPTIONS, false)

        borderColor = runCatching { LunaSettings.getColor(MOD_ID, KEY_BORDER_COLOR) }.getOrNull() ?: DEFAULT_BORDER_COLOR
        panelOpacity = (runCatching { LunaSettings.getFloat(MOD_ID, KEY_PANEL_OPACITY) }.getOrNull()
            ?: DEFAULT_PANEL_OPACITY).coerceIn(0f, 1f)
    }

    private fun bool(key: String, fallback: Boolean): Boolean =
        runCatching { LunaSettings.getBoolean(MOD_ID, key) }.getOrNull() ?: fallback

    // --- "Wipe saved preferences" pseudo-button --------------------------------------------------

    /**
     * LunaSettings only offers value fields, so the wipe is a boolean the player flips on and
     * applies. We turn it back off ourselves so it behaves like a one-shot action rather than a mode.
     * (The open menu keeps drawing it as on until it is reopened; the stored value is already off.)
     *
     * Order matters: the flag is disarmed **before** the wipe. If it could not be written back, a
     * wipe here would run again at every launch and keep eating new preferences, so we skip the wipe
     * and leave the request visible instead.
     */
    private fun handleWipeRequest() {
        if (!bool(KEY_WIPE_PREFS, false)) return
        if (!disarmWipeFlag()) {
            log.error(
                "Hullmods - Renewed: could not turn the 'Wipe saved preferences' setting back off, so the " +
                    "wipe was skipped (it would otherwise repeat on every launch). Turn it off in LunaSettings."
            )
            return
        }
        HullmodPrefs.wipeAll()
    }

    /** Writes `hmr_wipe_prefs = false` back to disk and into LunaLib's in-memory copy. Returns true
     *  only once LunaSettings actually reads the flag as off again. */
    private fun disarmWipeFlag(): Boolean = runCatching {
        val json = JSONUtils.loadCommonJSON(LUNA_SETTINGS_FILE)
        // A failed read hands back an empty object; saving that would clobber every other setting.
        // On a good read the key is always there (LunaLib writes the whole mod's block).
        if (!json.has(KEY_WIPE_PREFS)) return@runCatching false
        json.put(KEY_WIPE_PREFS, false)
        json.save()
        LunaSettings.SettingsCreator.refresh(MOD_ID)
        !bool(KEY_WIPE_PREFS, true)
    }.getOrDefault(false)
}
