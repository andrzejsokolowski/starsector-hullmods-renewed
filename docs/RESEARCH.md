# Hullmods - Renewed — Research Notes

Research pass before locking the design. Sources: vanilla `starsector-core`, Refit Filters
(full source, the key technical blueprint), and the modded factions/libraries on disk.

---

## 1. The problem, quantified

Hull-mod counts from `data/hullmods/hull_mods.csv`:

| Source | Hull mods |
|---|---|
| Vanilla | 291 |
| United Aurora Federation | 156 |
| Sephira Conclave | 217 |
| AoTD – Vaults of Knowledge | 156 |
| Industrial.Evolution | 58 |
| Loulan Industries | 56 |

There are **109 `hull_mods.csv` files** across the installed mod set. A heavily-modded game
easily exceeds **1,000 hull mods**, and the vanilla refit picker shows them as a flat,
category-filtered grid with no search, no hiding, and no batching. That is the pain point.

## 2. The vanilla hull-mod data model

`hull_mods.csv` columns (the ones that matter to us):

- `id`, `name` — identity.
- `tags` — *functional* tags used by game logic (e.g. `defensive, shields, merc, standard`).
- `uiTags` — *display* categories that drive the picker's category filter buttons
  (e.g. `Shields`, `Phase`, `Weapons`, `Special`, `Logistics`, `Defensive`). **This is the
  hook for a "Favourites" category** — the UI already groups by these.
- `hidden` — not shown in the codex / normal lists.
- `hiddenEverywhere` — not shown anywhere, including refit.
- `cost_frigate/dest/cruiser/capital` — ordnance point cost by hull size.
- `script` — the `HullModEffect` implementation class.

Key API surface (vanilla, non-obfuscated): `HullModSpecAPI` (has `getId`, `getDisplayName`,
`getUITags`, `getTags`, `isHidden`, `isHiddenEverywhere`, `getTier`, cost accessors),
`Global.getSettings().getAllHullModSpecs()` / `getHullModSpec(id)`, and on a fit:
`ShipVariantAPI.addPermaMod / addMod / removeMod / getHullMods / getNonBuiltInHullmods`.
A hull mod's installability on a given ship is gated by `HullModEffect.isApplicableToShip` /
`getUnapplicableReason`, so templates must respect per-ship applicability.

Implication: "blacklist", "favourite", and "template membership" are **per-player metadata
keyed by hull-mod id** — they are not properties of the spec. We store them ourselves (LunaLib
persistent data / a JSON), and apply them as a UI filter at refit time. We do **not** edit
anyone's CSV.

## 3. How the refit UI can be modified (the crucial part)

The vanilla refit screen and its pickers are **obfuscated** — no clean API to subclass. The
proven, release-stable technique (used by Refit Filters) is **runtime reflection driven by an
`EveryFrameScript`**:

1. An `EveryFrameScript` runs while paused. When `campaignUI.currentCoreTab == REFIT`, it
   grabs the core UI panel (`CampaignState.getCore()`, or via the encounter dialog when docked).
2. It walks the UI tree (`getChildrenCopy`, `findChildWithMethod`, `allChildsWithMethod`) to
   locate the relevant panel by a **known method name** rather than an obfuscated class name.
   Refit Filters finds the weapon picker via `getPickedWeaponSpec`; we'll find the **hull-mod
   panel** by the analogous method/marker it exposes.
3. It reads the item list (the child with an `addItem` method), maps each row back to its spec
   via `getTooltip().get(type = …SpecAPI)`, then **filters / reorders** the list
   (`clear` + `addItem`) and injects our own controls (search bar, Favourites toggle, Templates
   button) as sibling `CustomPanelAPI`s.
4. A guard (checking for an already-injected marker panel) makes it idempotent across frames.

This is **reflection at runtime, not bytecode patching**. Contrast with AoTD (Theory of
Toolbox / Vaults of Knowledge), which ships `asm-9.1.jar` + an `IndustryPatcher.jar` and
install scripts that rewrite core classes on disk — far more invasive and fragile. We don't
need that for a refit-UI overlay; Starficz's reflection approach is the right model.

### Starficz `UIFramework` — reusable, correctly licensed

Refit Filters bundles `org/starficz/UIFramework/**`, a Kotlin DSL for building Starsector UI
through reflection. Licensing (from its `LICENSE`):

- `org.starficz.UIFramework` → **LGPL-3.0-only** — usable as a library; we vendor it in our
  `src/` keeping its license header, and keep that package's source available.
- `org.starficz.refitfilters` → **CC0-1.0** (public domain) — we may copy patterns from it freely.

The framework gives us: `UIPanelAPI.CustomPanel { … }` builder, `Text`, `addImage`,
`addTextField`, tooltips, anchor/position helpers, a robust `ReflectionUtils`
(`invoke`/`get`/`set` + `getMethodsMatching` etc. via `MethodHandle`, with caching), and
`ExtendableCustomPanelPlugin`. This removes ~80% of the hard, brittle work.

### Kotlin runtime — solved, no fat jar

Refit Filters is Kotlin yet bundles no Kotlin stdlib. The runtime comes from
**LazyLib → `jars/internal/Kotlin-Runtime.jar`** (full kotlin-stdlib, loaded onto Starsector's
shared classloader). So: write in Kotlin, depend on LazyLib (and LunaLib, which depends on
LazyLib), `compileOnly` that runtime jar, and **bundle nothing** — avoiding classloader
conflicts with other Kotlin mods. This is wired into `build.gradle` already.

## 4. The three features → implementation sketch

- **Blacklist** — store a `Set<hullModId>`. In the picker filter step, drop blacklisted ids.
  A right-click / modifier-click on a row, or a toggle in our injected panel, edits the set.
  (Optional "show blacklisted" override toggle so they're recoverable.)
- **Favourites** — store a `Set<hullModId>`. Inject a **"★ Favourites"** category button
  alongside the vanilla `uiTags` filters; when active, show only favourites. Marking is a
  star toggle on each row.
- **Templates** — store `Map<templateName, List<hullModId>>`. Inject a **"Templates"** button
  opening a small panel: pick a template → install all members that are applicable to the
  current ship (respecting OP, S-mods, applicability, and skipping already-installed). Build
  templates from the current ship's installed mods or by multi-select.

**Persistence:** LunaLib persistent/campaign data (per-save) is the natural home; a global
JSON in the mod's config folder can hold cross-save defaults. To decide together: per-save vs
global (or both).

## 5. Reference mods on disk

| Mod | Use to us | Source available? |
|---|---|---|
| **Refit Filters** (Starficz) | The blueprint: refit-UI reflection + `UIFramework` | **Yes** (Kotlin) |
| **LazyLib** | Kotlin runtime provider; utils | jars only |
| **LunaLib** | Settings UI, persistence, fuzzy search (fuzzywuzzy) | jars only |
| AoTD Theory of Toolbox / Vaults of Knowledge (Kaysaar) | Custom-UI precedent, but via ASM core-patching (heavier path we avoid) | jars only |
| Ashlib | FX/util resources | jars only |
| UAF / Sephira Conclave / Loulan | Content mods — bulk hull mods to test against (no UI code) | jars only |

## 6. Open questions for the planning discussion

1. **Language:** Kotlin (recommended — reuses Starficz's framework) vs plain Java (heavier,
   reimplements the reflection/DSL).
2. **In-place overlay vs separate window:** inject controls into the vanilla picker (seamless,
   more reflection/pixel-fitting) vs a separate full-screen custom hull-mod manager opened by a
   button (more freedom, less seamless). Refit Filters does in-place.
3. **Persistence scope:** per-save, global, or both (with global defaults seeding new saves).
4. **Interaction model** for marking favourite/blacklist (modifier-click vs explicit toggles).
5. **Scope of v0.1:** which of the three features to ship first (suggest Blacklist + Favourites
   first since they share the filter pipeline; Templates second).
6. **Combat refit** (in-battle pre-deploy refit) — support it too, or campaign-only at first?
