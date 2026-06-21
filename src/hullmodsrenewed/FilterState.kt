package hullmodsrenewed

/**
 * Session-persistent state of the picker filters, driven by both the UI toggle buttons (persistent)
 * and the hold-keybinds (momentary). The filter pipeline reads the *effective* value, which is the
 * persistent toggle OR'd with the momentary hold (see [PickerController.applyFilter]).
 *
 * Survives picker re-opens within a session (it's an object), so your filter choices stick as you
 * refit different ships. Blacklist/favourite *membership* lives per-save in [HullmodPrefs]; this is
 * just which filters are active.
 */
object FilterState {
    var favouritesOnly = false
    var showBlacklisted = false
    var applicableOnly = true   // default ON: hide mods that can never go on this ship (e.g. carrier mod on a no-bay hull)
    // facet selections (design type / type / OP range / search) land here next.
}
