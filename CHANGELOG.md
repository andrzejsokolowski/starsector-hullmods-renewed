# Changelog

All notable changes to **Hullmods - Renewed** are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/), and the
project uses [Semantic Versioning](https://semver.org/) (fix = patch, feature =
minor, release = major).

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
