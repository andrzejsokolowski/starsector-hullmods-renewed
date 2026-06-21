# UI design — the left filter column

Agreed direction (2026-06-21). Replaces vanilla's bottom facet bar with a unified vertical
filter column in the (free) space left of the picker list.

## Layout (top → bottom)

- 🔍 **Search** (fuzzy; the one thing vanilla lacks)
- **Quick toggles:** ★ Favourites · Applicable only *(default ON)* · Show blacklisted
- **Design type** — multi-select checkboxes + counts (scrollable; ~18+ in a modded save)
- **Type / category** (`uiTags`) — multi-select checkboxes + counts
- **OP cost** — range slider
- *(later)* Tier / rarity
- **Reset filters**

## Decisions

1. **Keybinds are HOLD (momentary), not toggle.** UI buttons are persistent toggles. They
   compose: `effective = buttonState || keyHeld`. (Per-filter nuance: for *applicable-only*,
   which defaults ON, the hold momentarily turns it OFF — i.e. "hold to peek at N/A mods".)
2. **Facets are multi-select.** Within a group = OR (CJHM *or* Common); across groups = AND
   (designType AND type AND OP AND applicable AND search AND not-blacklisted).
3. **Mark modifiers (Ctrl=blacklist, Shift=favourite) are fixed for now**, but must become
   rebindable later (see BACKLOG).
4. **Facet counts** are computed off the *applicable-to-this-ship* set, not the raw full list.

## Architecture: one `FilterState`, two drivers

```
FilterState {
  favouritesOnly, applicableOnly, showBlacklisted : Bool   // persistent (buttons) + momentary (holds)
  selectedDesignTypes : Set<String>
  selectedTypes       : Set<String>
  searchText          : String
  opRange             : IntRange
}
```
- The proven row-filter pipeline reads `FilterState` and removes non-matching rows.
- Keybinds (hold) and UI buttons both feed the same `FilterState`; buttons re-sync their visuals
  each frame so key-driven and click-driven changes stay consistent.

## Keybinds via LunaLib

LunaLib has a native **Keybind** setting type (`LunaUIKeybindButton` / `createKeybindCard`), so
each hold-key is defined in `data/config/LunaSettings.csv` (`fieldType=Keybind`) and is
rebindable in LunaLib's mod-settings menu. Planned: favourites-only, applicable-only,
show-blacklisted, (maybe) toggle-panel.

## Technical approach

- **Do all filtering in our pipeline** and **hide vanilla's bottom facet bar** (rather than moving
  vanilla's buttons, which the dialog would re-lay-out on every `updateTable`). Vanilla's tag state
  stays "All" so the table stays fully populated; we filter it. We rebuild design-type/type facets
  ourselves from each row's `HullModSpecAPI` (manufacturer, `uiTags`, OP, tier).
- The column is injected when the picker opens, in the free zone left of the list.

## Build order (each screenshot-tuned)

1. **Left panel placed in the free zone + hide vanilla's bottom bar.** ← current
2. Our 3 toggles as buttons wired to `FilterState` + LunaSettings hold-keybinds.
3. Search box.
4. Design-type + type facets (multi-select + counts).
5. OP slider (+ tier).
