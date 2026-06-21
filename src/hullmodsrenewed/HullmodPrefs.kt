package hullmodsrenewed

import com.fs.starfarer.api.Global

/**
 * Per-save storage for the player's hull-mod preferences.
 *
 * Backed by `Global.getSector().getPersistentData()`, which is serialized with the campaign save,
 * so blacklist/favourites live per-playthrough (the design decision for v0.1). When there is no
 * sector (title-screen missions / simulator refit), we hand back a throwaway empty set so the UI
 * code degrades gracefully instead of crashing.
 */
object HullmodPrefs {

    private const val KEY_BLACKLIST = "hullmods_renewed_blacklist"
    private const val KEY_FAVOURITES = "hullmods_renewed_favourites"

    @Suppress("UNCHECKED_CAST")
    private fun set(key: String): MutableSet<String> {
        val sector = Global.getSector() ?: return mutableSetOf()
        val data = sector.persistentData
        var existing = data[key] as? MutableSet<String>
        if (existing == null) {
            existing = HashSet()
            data[key] = existing
        }
        return existing
    }

    fun blacklist(): MutableSet<String> = set(KEY_BLACKLIST)
    fun favourites(): MutableSet<String> = set(KEY_FAVOURITES)

    fun isBlacklisted(id: String): Boolean = blacklist().contains(id)
    fun isFavourite(id: String): Boolean = favourites().contains(id)

    /** Toggles blacklist membership. Returns the new state (true = now blacklisted). */
    fun toggleBlacklist(id: String): Boolean {
        val s = blacklist()
        return if (s.remove(id)) false else { s.add(id); true }
    }

    /** Toggles favourite membership. Returns the new state (true = now a favourite). */
    fun toggleFavourite(id: String): Boolean {
        val s = favourites()
        return if (s.remove(id)) false else { s.add(id); true }
    }
}
