# Feature backlog

Requested features not yet scheduled into the current milestone.

---

## "Installable only" filter toggle  *(requested 2026-06-21)*

**Ask:** a switch in the hull-mod picker that shows **only hull mods that can actually be
installed on the current ship**, hiding ones that are not applicable. Motivation: in a heavily
modded game many hull mods are restricted to a specific ship / ship type / hull size, and they
clutter the list for ships they can never go on.

**Two criteria to distinguish (confirm scope when implementing):**
1. **Applicable to this ship** — passes the hull mod's ship/type/size restrictions
   (`HullModEffect.isApplicableToShip(ship)` / `getUnapplicableReason`; the dialog's own
   `isApplicable(spec, ship)`). *This is the primary ask.*
2. **Affordable right now** — enough free OP to install (dialog's `canAfford(spec, ship)`;
   `canInstall` = applicable AND affordable). The user phrased it "if you have the OP or not",
   so OP-affordability is a **secondary / optional** sub-criterion — likely a separate toggle,
   not bundled into the applicability filter.

**Default behaviour — to decide:** the user said they "obviously don't want to see [N/A mods]
by default", which suggests the applicability filter could default **ON** (hide non-applicable),
with the toggle used to reveal everything. Counterpoint: vanilla shows non-applicable mods greyed
out, and some players rely on seeing them. Recommendation: ship a toggle whose default is
configurable (LunaLib setting), leaning default-ON for the applicability filter, default-OFF for
the OP-affordability one.

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
