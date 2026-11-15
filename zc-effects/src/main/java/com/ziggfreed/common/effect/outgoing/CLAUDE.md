# effect/outgoing/ - per-type damage bonus on the engine

- The one applier of zc-entity's per-type PERCENT damage bonus sources (`TypedDamageBonuses`, R567): its `DamageBonusReconciler.CHANNEL` on the one stat-to-effect pass (`effect/sync/`) compares each living carrier's summed sources, a creature's as a player's (R860), with its `Zig_DamageBonus_*` effects after `EntityStatsSystems.Recalculate` and reconciles only a difference.
- One every-type effect (`Zig_DamageBonus_Every_<n>`, keyed on every damage type the loaded files name, `DamageTypes`) and one per type with its own sources, keyed on the type alone (the engine's outgoing walk covers its children); a type's total is held to its `DamageBonusMax` before the split. A modifier on a creature's every-type `DamageBonus` (an endless wave's step) rides the every-type effect like any other source.
- Flats and route lines are not here: zc-entity's `TypedDamageBonusSystem` adds them per hit (flats on a direct hit only).
