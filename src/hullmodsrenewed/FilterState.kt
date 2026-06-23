package hullmodsrenewed

/**
 * Session-persistent state of the picker filters, driven by both the UI toggle buttons (persistent)
 * and the hold-keybinds (momentary). The filter pipeline reads the *effective* value, which is the
 * persistent toggle OR'd with the momentary hold (see [PickerController.applyFilter]).
 *
 * Survives picker re-opens within a session (it's an object), so all your filter choices -- toggles,
 * search text, and facet selections -- stick as you close/reopen the picker and refit different ships
 * (use "Reset filters" to clear them). Blacklist/favourite *membership* lives per-save in
 * [HullmodPrefs]; this is just which filters are active.
 */
object FilterState {
    var favouritesOnly = false
    var showBlacklisted = false
    var applicableOnly = true   // default ON: hide mods that can never go on this ship (e.g. carrier mod on a no-bay hull)
    var searchText = ""         // substring filter on hull-mod name + design type

    // Multi-select facets. Empty set = no filter for that group. Within a group: OR. Across groups: AND.
    val selectedDesignTypes: MutableSet<String> = mutableSetOf()   // by manufacturer
    val selectedTypes: MutableSet<String> = mutableSetOf()         // by uiTag

    // Active custom-group filters (1..HullmodPrefs.GROUP_COUNT). Empty = no filter; within: OR; AND with the rest.
    val selectedGroups: MutableSet<Int> = mutableSetOf()

    fun clearTransient() {
        searchText = ""
        selectedDesignTypes.clear()
        selectedTypes.clear()
        selectedGroups.clear()
    }
}
