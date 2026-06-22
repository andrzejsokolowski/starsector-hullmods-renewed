# Changelog

All notable changes to **Hullmods - Renewed** are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/), and the
project uses [Semantic Versioning](https://semver.org/) (fix = patch, feature =
minor, release = major).

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
- **Toggle filters**: *Favourites only*, *Show blacklisted*, *Applicable only*
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
- Decoupled from Refit Filters: the vendored UI framework is relocated to
  `hullmodsrenewed.uiframework`, so the two mods can run side by side.

## [0.2.0] - earlier

- Favourites-only filter logic; "Applicable only" structural filter (per-hull).
- Marking overlay foundation (Ctrl/Shift+click) with click pass-through.

## [0.1.0] - earlier

- Initial mod: per-save blacklist + favourites store, refit-picker injector,
  blacklist filtering.
