# Changelog

All notable changes to **Hullmods - Renewed** are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/), and the
project uses [Semantic Versioning](https://semver.org/) (fix = patch, feature =
minor, release = major).

## [1.3.1] - 2026-06-30

### Changed
- The assignment marker column is now right-aligned to hug the left edge of the mod list (instead of
  floating mid-gutter), so each row's trash/star/group markers read as that row's column. Icons and
  group digits are slightly larger for legibility.

## [1.3.0] - 2026-06-30

### Added
- **Custom-group names** — name any of the ten custom groups so you remember what's in them. A
  **Name groups…** button under the CUSTOM GROUPS squares opens a small modal (pick a group, type a
  name, Save / Cancel; Enter saves, Esc cancels). Names show as a hover tooltip on each square (with
  the group's size and shortcut key) and in the CUSTOM GROUPS heading when a single group is selected.
  Names are stored per-save.
- **Assignment marker column** — a new gutter to the left of the mod list shows, per row, where a
  hull-mod is assigned: a red trash icon if blacklisted, a yellow star if favourited, and the digits
  of every custom group it belongs to. Markers track scrolling and are clipped to the list.

## [1.2.1] - 2026-06-28

### Fixed
- Removing a hull-mod from a ship now refreshes the picker list. Previously the table only rebuilt on
  *installing* a mod, so with "Applicable only" on, removing a mod left its now-applicable
  mutually-exclusive counterpart hidden until the next unrelated rebuild. The installed-mods loadout is
  now part of the rebuild signature, so both installing and removing refresh the list (and re-check
  applicability) while keeping the scroll position.

## [1.2.0] - 2026-06-23

### Added
- **Custom groups** — ten RTS-style hull-mod groups. Hover a mod in the list and press a number key
  **1-9 (or 0 for the tenth)** to toggle it in that group. A new **CUSTOM GROUPS** row of ten
  numbered square buttons sits above TYPE in the filter panel; clicking a square shows that group
  (plain click = only that group, Shift/Ctrl+click adds, re-clicking the sole selection clears),
  matching the TYPE/DESIGN TYPE facets. Membership is stored per-save, and marking a mod into a group
  keeps the list's scroll position. The legend documents the shortcut.

## [1.1.2] - 2026-06-23

### Fixed
- Blacklisting or favouriting a mod (Ctrl/Shift+click) no longer jumps the list to the top. Marking
  changes the filter signature and so rebuilds the table; the scroll position is now kept across that
  rebuild, the same as for refit actions.

## [1.1.1] - 2026-06-23

### Fixed
- The hull-mod list no longer jumps to the top when you add flux vents or capacitors (or otherwise
  change the ship's loadout) with the picker open. The scroll position is now kept across the
  picker's table rebuild, the same way it is for installing a mod. Switching to a different hull
  still starts at the top, and changing a filter still shows the top.

## [1.1.0] - 2026-06-23

### Changed
- Filter selections (search text, type/design-type facets, and the toggles) now persist when you
  close and reopen the hull-mod picker within a session, matching vanilla. Previously the search and
  facet selections were cleared every time the picker opened. Use **Reset filters** to clear them.

## [1.0.3] - 2026-06-23

### Fixed
- The hull-mod list no longer jumps back to the top when you install a mod. We no longer rebuild
  the table on install (installing rebuilds the preview ship, which we were mistaking for a filter
  change); the scroll position and column sort are kept.

## [1.0.2] - 2026-06-22

### Fixed
- Corrected the repository name in the version file URLs (`masterVersionFile` / `directDownloadURL`)
  so the version checker resolves the master version file and download.

## [1.0.1] - 2026-06-22

### Added
- Version-checker forum-thread link (`modThreadId`), so the updater can jump to the forum thread.

### Fixed
- The vanilla column sort (OP / Design type / Installed) no longer resets when you install a hull
  mod with the left-panel filters active. The active sort is captured and re-applied after the
  picker rebuilds its table.

## [1.0.0] - 2026-06-22

First public release — the hull-mod picker overhaul is feature-complete (blacklist,
favourites, full left-side filter panel, applicable-to-ship filter, vanilla bar
replaced). See 0.3.0 and 0.4.0 below for the feature details.

### Added

- The mod list now stretches to fill the space freed by removing the vanilla filter
  bar, keeping the column headers fixed.

## [0.4.0] - 2026-06-22

### Removed

- The **vanilla bottom design-type filter bar** — the left-side panel fully replaces it. The vanilla
  filter is held wide open so every available mod still reaches the list.

### Changed

- **Templates dropped from scope** — the planned "install a named group of hull mods in one click"
  feature will not be built. Mod description updated to match.

## [0.3.0] - 2026-06-22

The full left-side filter column milestone. The greyed-out ship-selector space in
the refit hull-mod picker is now a proper filter panel.

### Added

- **Filter column** on the left of the picker (search + toggles + facets + legend).
- **Search** box — live substring match on hull-mod name and design type.
- **Toggle filters**: _Favourites only_, _Show blacklisted_, _Applicable only_
  (on by default — hides mods that can never go on the current hull).
- **Multi-select facets** with live counts, sourced from the mods you actually have:
  - **TYPE** (2-column grid) and **DESIGN TYPE** (full-width list).
  - Click = only that one · Shift/Ctrl+click = add/remove · click the sole
    selection again to clear. OR within a group, AND across groups.
  - The facet list scrolls (mouse wheel + scrollbar).
- **Marking**: Ctrl+click a mod = blacklist, Shift+click = favourite.
- **Hold-keys**: hold `` ` `` = favourites only, hold Alt = reveal blacklisted.
- **Reset filters** button.
- On-screen **legend** of the controls.

### Changed

- Facet counts reflect the picker's available-to-you mod list, independent of the
  vanilla bottom-bar filter.

### Notes

- The vanilla bottom facet bar is still present (to be replaced next).

## [0.2.0] - earlier

- Favourites-only filter logic; "Applicable only" structural filter (per-hull).
- Marking overlay foundation (Ctrl/Shift+click) with click pass-through.

## [0.1.0] - earlier

- Initial mod: per-save blacklist + favourites store, refit-picker injector,
  blacklist filtering.
