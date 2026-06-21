# Hullmods - Renewed

A Starsector utility mod that overhauls the refit screen's hull-mod picker to tame the
hundreds of hull mods that pile up in a heavily-modded game.

## Goals

- **Blacklist** — hide individual hull mods from the picker so they stop cluttering the list.
- **Favourites** — flag hull mods to surface them in a dedicated "Favourites" category/tab.
- **Templates** — define named groups of hull mods and install the whole group with one click.

Works with vanilla and modded hull mods alike.

## Status

Early scaffolding. The design is being planned — see [`docs/RESEARCH.md`](docs/RESEARCH.md).

## Tech

- **Language:** Kotlin (+ a small Java entry point), compiled together from `src/`.
- **Build:** Gradle (`./gradlew build` → `jars/HullmodsRenewed.jar`).
- **Runtime deps:** [LunaLib](https://fractalsoftworks.com/forum/index.php?topic=25658)
  (settings UI + persistence + fuzzy search) and
  [LazyLib](https://fractalsoftworks.com/forum/index.php?topic=5444) (provides the Kotlin
  runtime on Starsector's shared classloader, so this mod ships **no** Kotlin stdlib of its own).
- **UI approach:** runtime reflection into the refit screen, following the technique from
  Refit Filters by Starficz. Its `UIFramework` (LGPL-3.0) is vendored under
  `src/org/starficz/UIFramework`.

## Building

1. Edit `starsectorPath` in `gradle.properties` if your install isn't at `D:/Games/StarSector`.
2. `./gradlew build`
3. Copy/symlink this folder into `<Starsector>/mods/` (or build straight into it).

## Credits & licensing

- `src/org/starficz/UIFramework/**` — © Starficz, **LGPL-3.0-only** (vendored, see header).
- Everything else — see `LICENSE`.
