# Hullmods - Renewed

A Starsector utility mod that overhauls the refit screen's hull-mod picker to tame the
hundreds of hull mods that pile up in a heavily-modded game.

## Goals

- **Blacklist** — hide individual hull mods from the picker so they stop cluttering the list.
- **Favourites** — flag hull mods to surface them in a dedicated "Favourites" category/tab.

Works with vanilla and modded hull mods alike.

## Status

1.0.0 Released. Works and tested.

## Tech

- **Language:** Kotlin (+ a small Java entry point), compiled together from `src/`.
- **Build:** Gradle (`./gradlew build` → `jars/HullmodsRenewed.jar`).
- **Runtime deps:** [LunaLib](https://fractalsoftworks.com/forum/index.php?topic=25658)
  (settings UI + persistence + fuzzy search) and
  [LazyLib](https://fractalsoftworks.com/forum/index.php?topic=5444) (provides the Kotlin
  runtime on Starsector's shared classloader, so this mod ships **no** Kotlin stdlib of its own).

## Building

1. Edit `starsectorPath` in `gradle.properties` if your install isn't at `D:/Games/StarSector`.
2. `./gradlew build`
