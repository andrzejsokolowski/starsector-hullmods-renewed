package hullmodsrenewed

import com.fs.starfarer.api.Global
import org.json.JSONArray
import org.json.JSONObject
import org.lazywizard.lazylib.JSONUtils

/**
 * Per-**installation** storage for the player's hull-mod preferences: the blacklist, the favourites,
 * the ten custom groups and their names.
 *
 * Backed by a single JSON file in Starsector's common data folder (`saves/common/` — see
 * [COMMON_FILE]), so a set of marks made in one playthrough is there in the next one too. Loaded
 * lazily on first access and written back on every change (the file is tiny and edits are
 * user-driven, so a crash or an alt-F4 can never lose a mark).
 *
 * Everything is stored as **plain hull-mod ids**; nothing here ever resolves an id to a spec. That
 * keeps the store safe across mod-list changes: ids for hull-mods the player has not learned yet, or
 * that belong to a mod which is currently disabled, are simply carried through untouched and start
 * matching again the moment those mods show up. (See [unknownIdCount] for the read-only report the
 * group tooltips use.)
 *
 * Older saves kept these in `Global.getSector().getPersistentData()`. [migrateFromSave] folds that
 * legacy data into the shared file once per save, so upgrading never loses existing marks.
 */
object HullmodPrefs {

    /** Number of custom (RTS-style) hull-mod groups, addressed 1..[GROUP_COUNT] (number keys 1-9 then
     *  0 for the last one). */
    const val GROUP_COUNT = 10

    /** Path under `saves/common/`. Starsector appends `.data` to the file on disk. */
    private const val COMMON_FILE = "hullmods_renewed/preferences.json"

    private const val FORMAT_VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_BLACKLIST = "blacklist"
    private const val KEY_FAVOURITES = "favourites"
    private const val KEY_GROUPS = "groups"
    private const val KEY_GROUP_NAMES = "groupNames"

    // --- Legacy per-save keys (v1.5.0 and earlier) ---------------------------------------------
    private const val LEGACY_BLACKLIST = "hullmods_renewed_blacklist"
    private const val LEGACY_FAVOURITES = "hullmods_renewed_favourites"
    private const val LEGACY_GROUP_PREFIX = "hullmods_renewed_group_"
    private const val LEGACY_GROUP_NAME_PREFIX = "hullmods_renewed_groupname_"

    /** Set on a save once its legacy per-save marks have been folded into the shared file, so the
     *  merge happens exactly once and later removals aren't undone on the next load. */
    private const val LEGACY_MIGRATED = "hullmods_renewed_migrated_to_common"

    private val log = Global.getLogger(HullmodPrefs::class.java)

    private val blacklistSet = LinkedHashSet<String>()
    private val favouriteSet = LinkedHashSet<String>()
    private val groupSets = Array(GROUP_COUNT) { LinkedHashSet<String>() }
    private val groupNames = arrayOfNulls<String>(GROUP_COUNT)

    private var loaded = false

    // --- Load / save ---------------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true          // set first: a failed read must not retry on every frame
        runCatching { readFile() }.onFailure {
            log.error("Hullmods - Renewed: could not read $COMMON_FILE, starting from empty preferences.", it)
        }
    }

    private fun readFile() {
        val json: JSONObject = JSONUtils.loadCommonJSON(COMMON_FILE)
        if (json.length() == 0) return          // no file yet (or an empty one): nothing to restore

        readIds(json.optJSONArray(KEY_BLACKLIST), blacklistSet)
        readIds(json.optJSONArray(KEY_FAVOURITES), favouriteSet)

        val groups = json.optJSONObject(KEY_GROUPS)
        if (groups != null) for (i in 1..GROUP_COUNT) readIds(groups.optJSONArray(i.toString()), groupSets[i - 1])

        val names = json.optJSONObject(KEY_GROUP_NAMES)
        if (names != null) for (i in 1..GROUP_COUNT) {
            val name = names.optString(i.toString(), "").trim()
            if (name.isNotEmpty()) groupNames[i - 1] = name
        }
    }

    /** Copies the string entries of [array] into [into], skipping anything blank or non-textual.
     *  Ids are never validated against loaded specs — see the class doc. */
    private fun readIds(array: JSONArray?, into: MutableSet<String>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val id = array.optString(i, "").trim()
            if (id.isNotEmpty()) into.add(id)
        }
    }

    /** Writes the whole store back to the common folder. Never throws — a failed write is logged and
     *  the in-memory state stays authoritative for the rest of the session. */
    private fun save() {
        runCatching {
            val json = JSONUtils.CommonDataJSONObject(COMMON_FILE)
            json.put(KEY_VERSION, FORMAT_VERSION)
            json.put(KEY_BLACKLIST, JSONArray(blacklistSet))
            json.put(KEY_FAVOURITES, JSONArray(favouriteSet))

            val groups = JSONObject()
            for (i in 1..GROUP_COUNT) {
                val members = groupSets[i - 1]
                if (members.isNotEmpty()) groups.put(i.toString(), JSONArray(members))
            }
            json.put(KEY_GROUPS, groups)

            val names = JSONObject()
            for (i in 1..GROUP_COUNT) groupNames[i - 1]?.let { names.put(i.toString(), it) }
            json.put(KEY_GROUP_NAMES, names)

            json.save()
        }.onFailure {
            log.error("Hullmods - Renewed: could not write $COMMON_FILE; this session's changes are not saved.", it)
        }
    }

    // --- Migration from per-save storage --------------------------------------------------------

    /**
     * Folds a save's legacy per-save marks (v1.5.0 and earlier) into the shared file, once per save.
     * Sets are merged (union) rather than replaced, and a group name only fills a slot that has none,
     * so loading an old save never clobbers preferences built up elsewhere.
     *
     * The legacy entries are deliberately left in the save (only a marker is added), so downgrading
     * to an older build still finds its data.
     */
    @Suppress("UNCHECKED_CAST")
    fun migrateFromSave() {
        val sector = Global.getSector() ?: return
        val data = sector.persistentData ?: return
        if (data[LEGACY_MIGRATED] == true) return

        ensureLoaded()
        runCatching {
            var found = false
            (data[LEGACY_BLACKLIST] as? Collection<*>)?.let { found = true; blacklistSet.addAll(it.filterIsInstance<String>()) }
            (data[LEGACY_FAVOURITES] as? Collection<*>)?.let { found = true; favouriteSet.addAll(it.filterIsInstance<String>()) }
            for (i in 1..GROUP_COUNT) {
                (data[LEGACY_GROUP_PREFIX + i] as? Collection<*>)?.let {
                    found = true; groupSets[i - 1].addAll(it.filterIsInstance<String>())
                }
                val name = (data[LEGACY_GROUP_NAME_PREFIX + i] as? String)?.trim()
                if (!name.isNullOrEmpty()) {
                    found = true
                    if (groupNames[i - 1] == null) groupNames[i - 1] = name
                }
            }
            data[LEGACY_MIGRATED] = true
            if (found) {
                save()
                log.info("Hullmods - Renewed: migrated this save's hull-mod preferences into $COMMON_FILE.")
            }
        }.onFailure {
            log.error("Hullmods - Renewed: failed to migrate per-save hull-mod preferences.", it)
        }
    }

    // --- Blacklist / favourites -----------------------------------------------------------------

    /** Read-only view of the blacklisted hull-mod ids. */
    fun blacklist(): Set<String> { ensureLoaded(); return blacklistSet }

    /** Read-only view of the favourited hull-mod ids. */
    fun favourites(): Set<String> { ensureLoaded(); return favouriteSet }

    fun isBlacklisted(id: String): Boolean = blacklist().contains(id)
    fun isFavourite(id: String): Boolean = favourites().contains(id)

    /** Toggles blacklist membership. Returns the new state (true = now blacklisted). */
    fun toggleBlacklist(id: String): Boolean = toggle(blacklistSet, id)

    /** Toggles favourite membership. Returns the new state (true = now a favourite). */
    fun toggleFavourite(id: String): Boolean = toggle(favouriteSet, id)

    private fun toggle(set: MutableSet<String>, id: String): Boolean {
        ensureLoaded()
        val added = if (set.remove(id)) false else { set.add(id); true }
        save()
        return added
    }

    // --- Custom groups (RTS-style 1..GROUP_COUNT) ----------------------------------------------

    /** Read-only members of custom group [index] (1..[GROUP_COUNT]). */
    fun groupMembers(index: Int): Set<String> {
        if (index !in 1..GROUP_COUNT) return emptySet()
        ensureLoaded()
        return groupSets[index - 1]
    }

    /** Toggles membership of [id] in custom group [index]. Returns the new state (true = now in the group). */
    fun toggleGroup(index: Int, id: String): Boolean {
        if (index !in 1..GROUP_COUNT) return false
        ensureLoaded()
        return toggle(groupSets[index - 1], id)
    }

    /** Player-given name for custom group [index], or "" if unnamed. */
    fun groupName(index: Int): String {
        if (index !in 1..GROUP_COUNT) return ""
        ensureLoaded()
        return groupNames[index - 1] ?: ""
    }

    /** Sets (or, when [name] is blank, clears) the name of custom group [index]. */
    fun setGroupName(index: Int, name: String) {
        if (index !in 1..GROUP_COUNT) return
        ensureLoaded()
        groupNames[index - 1] = name.trim().takeIf { it.isNotEmpty() }
        save()
    }

    /** A short label for custom group [index]: its name if set, else "Group N" (N = the on-screen digit). */
    fun groupLabel(index: Int): String =
        groupName(index).ifBlank { "Group ${index % 10}" }

    // --- Maintenance ----------------------------------------------------------------------------

    /**
     * Erases every stored preference — blacklist, favourites, group membership and group names — and
     * writes the now-empty file. Driven by the "Wipe saved preferences" toggle in LunaSettings.
     *
     * Also stamps the current save (if any) as migrated, so a wipe done in the campaign isn't undone
     * by that save's legacy per-save marks flowing back in on the next load.
     */
    fun wipeAll() {
        ensureLoaded()
        blacklistSet.clear()
        favouriteSet.clear()
        groupSets.forEach { it.clear() }
        groupNames.fill(null)
        save()
        runCatching { Global.getSector()?.persistentData?.put(LEGACY_MIGRATED, true) }
        log.info("Hullmods - Renewed: wiped all saved hull-mod preferences.")
    }

    /** Total number of marks across everything, for a quick "is there anything stored" check. */
    fun totalMarkCount(): Int {
        ensureLoaded()
        return blacklistSet.size + favouriteSet.size + groupSets.sumOf { it.size }
    }

    // --- Reporting on ids we can't resolve right now ---------------------------------------------

    /** Ids of every hull-mod spec currently loaded, or null if the spec list isn't readable yet.
     *  Cached: the spec list is fixed once the game has loaded. */
    private var knownIdCache: Set<String>? = null

    private fun knownIds(): Set<String>? {
        knownIdCache?.let { return it }
        val ids = runCatching {
            Global.getSettings().allHullModSpecs?.mapNotNull { it?.id }?.toHashSet()
        }.getOrNull()
        if (ids.isNullOrEmpty()) return null
        knownIdCache = ids
        return ids
    }

    /**
     * How many of [ids] don't match any hull-mod currently loaded — i.e. marks kept for mods that are
     * disabled or gone. Purely informational (shown in the group tooltips); such ids are never pruned,
     * so re-enabling the mod brings the marks straight back. Returns 0 if the spec list isn't
     * available, so this can never make a caller misreport.
     */
    fun unknownIdCount(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val known = knownIds() ?: return 0
        return ids.count { it !in known }
    }
}
