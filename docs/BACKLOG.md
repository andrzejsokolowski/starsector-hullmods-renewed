# Feature backlog

Requested features not yet scheduled into the current milestone.

---

## Cut from scope

- **Templates** (named groups of hull mods installed in one click) — dropped 2026-06-22. The user
  decided they'd effectively never be used, so this won't be built.

---

## "Applicable to this ship" filter toggle  *(requested 2026-06-21, clarified same day)*

**Ask:** a switch in the hull-mod picker that hides hull mods that can **never** apply to this
kind of ship. Example given: a carrier hull mod (needs hangar bays) should not appear for a ship
with no bays. Motivation: in a heavily modded game many hull mods are restricted by hull
type/characteristics and clutter the list for ships they can never go on.

**Scope — structural applicability only. NOT OP affordability.** The user explicitly does *not*
want an OP-based filter ("if you have the OP or not"). The filter is purely "is this mod ever
applicable to this hull?", independent of current ordnance points.

- Use `HullModEffect.isApplicableToShip(ship)` (public API) or the dialog's own
  `isApplicable(spec, ship)` — both ignore OP. `getUnapplicableReason` available for tooltips.
- Edge case to note (not block on): `isApplicableToShip` can also return false for *transient*
  reasons (e.g. conflicts with a currently-installed mod), not only permanent hull restrictions.
  There's no clean "ever applicable, ignoring current loadout" API. Ship with `isApplicableToShip`
  as the proxy — it nails the carrier/bays case; revisit only if transient hiding annoys in play.

**Default behaviour:** the user "obviously" doesn't want N/A mods by default → the toggle defaults
**ON** (hides non-applicable). Provide a LunaLib setting to change the default, and the in-picker
switch to flip it per-session.

**Implementation notes (from the M1 spike — see SPIKE-FINDINGS.md):**
- Reuse the M2 row-filter pipeline in `PickerController.applyBlacklistFilter` (generalise it into
  a multi-criterion filter): for each row, `row.getData() as HullModSpecAPI`, then test
  applicability and remove if filtered.
- Need the **current ship/variant** reference. Routes: the dialog's `getRefitPanel()`, the
  `ModWidget.syncWithCurrentVariant(HullVariantSpec)` variant, or a `ShipAPI` reachable from the
  refit panel. Prefer the dialog's own `isApplicable(spec, ship)` / `canInstall` /`canAfford`
  (it already has the ship internally) — pass `row.getData()` (the obfuscated `O00O` = the spec)
  back into those methods via reflection, or use the public `HullModEffect` API directly.
- UI: a small toggle (area checkbox) injected alongside the future search bar / Favourites tab.

**Slot:** fold into **M3** (filtering features) since it shares the filter pipeline.

---

## Rebindable mark modifiers  *(requested 2026-06-21)*

Currently the mark gestures are fixed: **Ctrl+click = blacklist**, **Shift+click = favourite**.
Make these modifier keys rebindable (via LunaLib settings, alongside the filter hold-keybinds).
Lower priority than the filter UI, but wanted eventually.
