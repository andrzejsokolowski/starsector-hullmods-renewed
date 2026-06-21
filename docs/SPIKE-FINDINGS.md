# Spike findings — the refit hull-mod UI (M1)

Goal: locate the obfuscated refit hull-mod picker and confirm we can filter/augment it via
reflection. **Result: fully confirmed.** Method derived by `javap` against
`starsector-core/starfarer_obf.jar` (game 0.98a-RC8, build 2025-04-18).

> Starsector obfuscation is *partial*: class names are mangled, but **method and field names are
> mostly preserved**, and public API types (e.g. `HullModSpecAPI`) are never obfuscated. That's
> what makes the reflection-by-method-name approach robust across releases. Obfuscated *class*
> names (below) can shift between versions — match by method signatures, not class names.
>
> ⚠️ Windows gotcha: the obf jar contains case-only-distinct entries (`B.class` vs `b.class`,
> `O00O.class` vs `o00o.class`). Do **not** extract it on a case-insensitive FS — they collide.
> Inspect straight from the jar: `javap -cp starfarer_obf.jar com.fs.starfarer....`

## Class map

| Role | Obfuscated class | Key members (stable names) |
|---|---|---|
| Hull-mod picker popup | `com.fs.starfarer.coreui.refit.ModPickerDialogV3` | `getAddedPermaMods()`, `getAddedSModdedBuiltIns()`, `hullmodMatchesFilters(spec,ship)`, `canInstall`, `canAfford`, `isApplicable`, `updateTable()`, `updateTags(bool)`, `tagsChanged()`, `getRefitPanel()`, `isPermMode()`, `permOnlyMode`, `syncUIWithVariant()`; holds a `UITable` field + a `tags` filter field |
| Persistent refit hull-mod panel | `com.fs.starfarer.coreui.refit.ModWidget` | **`addMod(String id)`** (install by id — for Templates), `removeNotApplicableMods()`, `syncWithCurrentVariant(HullVariantSpec)`, `getAdd()`, `getPerm()`, `dialogDismissed()` |
| List widget | `com.fs.starfarer.campaign.ui.UITable` | `getRows(): List<B>`, `addRow(B)`, `removeRow(B)`, `clear()`, `suspendRecompute()`/`resumeRecompute()`, `sort()`, `getRowForData(Object): B`, `setEmptyText(String)` |
| Table row | `com.fs.starfarer.campaign.ui.B` | **`getData(): Object`** (the row's hull-mod spec), `getButton()`, `getCol(int)` |
| Hull-mod spec impl | `com.fs.starfarer.loading.specs.O00O` | `implements HullModSpecAPI` → `getId()`, `getDisplayName()`, `getUITags()`, `getTags()`, `isHidden()`, `isHiddenEverywhere()`, `getEffect()` |

## How the pieces fit (the implementation recipe)

**Find the open picker** (mirrors Refit Filters finding `WeaponPickerDialog` via
`getPickedWeaponSpec`): from the refit core UI, `allChildsWithMethod("getAddedPermaMods")`
(unique to `ModPickerDialogV3`). It only exists while the picker popup is open.

**Get the list:** the dialog has exactly one field of type `UITable` → fetch via
`dialog.get(type = UITable::class.java)` (UIFramework reflection; unambiguous).

**Map rows → mods:** `table.getRows()` → `List<B>`; for each `row`,
`row.getData() as? HullModSpecAPI` → read `.getId()`, `.getUITags()`, etc. Pure public-API.

**Filter (Blacklist / Favourites):** wrap in `suspendRecompute()` … `resumeRecompute()`; drop
rows whose id is blacklisted (unless "show blacklisted" is on); in Favourites mode keep only
favourite ids. Re-apply each frame behind an idempotency guard (the dialog rebuilds its table on
`updateTable()` / tag changes). Use `setEmptyText()` for the empty state.

**Inject controls:** add a `CustomPanel { TextField(...); Text("★") }` (vendored UIFramework)
near the dialog's category-tag bar — a search box (fuzzy match via LunaLib's fuzzywuzzy) and a
"★ Favourites" toggle. Marking favourite/blacklist per row: simplest is modifier-click
(e.g. ctrl = blacklist, shift = favourite) intercepted around `tableRowSelected`, or small
overlay buttons per row — to finalize in M3.

**Templates (v0.2):** `ModWidget.addMod(id)` installs one hull mod; loop a template's ids,
gating on `HullModEffect.isApplicableToShip(...)` / the dialog's `canInstall`/`canAfford`, skip
already-installed. Built-in/S-mods go through the dialog's perm mode.

## Confidence

High. The only per-release-fragile inputs are the obfuscated **class names**; everything we
actually call is either public API (`HullModSpecAPI`, `HullModEffect`) or a stable method name
matched by signature. Same robustness profile as Refit Filters, which survives release updates.
