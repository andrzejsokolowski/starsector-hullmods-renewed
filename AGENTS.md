# Hullmods - Renewed

Overhauls the refit screen's hull mod picker: a filter panel on the left, blacklist, favourites
and custom groups. These instructions apply to this repository, including when it is opened
without the parent mod workspace. The workspace `AGENTS.md` one folder up governs releases,
packaging, Git identity and communication.

## Notion

Tasks, history and the ideas Andrzej dropped: the **Hullmods - Renewed board** in Andrzej's
Notion, under **Starsector mods** (`collection://a6c678b8-f838-49e1-9c9b-10e70d757aa3`). Working
rules and game API notes: **Claude Information**
(`collection://7bf9b1d6-b9e5-4b62-87f1-757bbaa51919`), starting with "Start here: working on
Starsector mods through Notion". Read the board before working, give every task a ticket (ticket
ids start with HMR) and keep it current. Hullmods - Renewed is also bundled in Coalesced Little
Mods: bring every change over there too. The Notion tools are deferred: load them with ToolSearch
first.

## Essentials

- id `hullmods_renewed`, package `hullmodsrenewed`, Kotlin plus a small Java entry, Gradle.
  LazyLib provides the Kotlin runtime: bundle no Kotlin stdlib.
- Build: `./gradlew jar` gives `jars/HullmodsRenewed.jar`. Package: stage `HullmodsRenewed/` and
  write `Hullmods-Renewed.zip` with Python `zipfile` (see "Packaging the ZIP" in Notion).
- Vendors Starficz's LGPL-3.0 `UIFramework` relocated to `hullmodsrenewed.uiframework`.
- Marks are stored as bare ids and never resolved against the loaded spec list. Do not add spec
  lookups to the load or save path.
- Templates and rebindable keys were cut by Andrzej. Do not build them.
- Design and research notes in `docs/`, release history in `CHANGELOG.md`.

## Update metadata

Keep `mod_info.json`, `hullmods_renewed.version` and `data/config/version/version_files.csv`
consistent. Forum topic 35619. The repo name has the `starsector-` prefix.

- updateCheckURL and masterVersionFile:
  `https://raw.githubusercontent.com/andrzejsokolowski/starsector-hullmods-renewed/main/hullmods_renewed.version`
- directDownloadURL, asset `Hullmods-Renewed.zip`:
  `https://github.com/andrzejsokolowski/starsector-hullmods-renewed/releases/latest/download/Hullmods-Renewed.zip`
