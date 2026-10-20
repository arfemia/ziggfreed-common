# effect/outgoing/ - per-type damage bonus on the engine

- The one applier of zc-entity's per-type PERCENT damage bonus sources (`TypedDamageBonuses`, R567): `DamageBonusSyncSystem` compares each living player's summed sources with their `Zig_DamageBonus_*` effects every tick after `EntityStatsSystems.Recalculate` and reconciles only a difference.
- One every-type effect (`Zig_DamageBonus_Every_<n>`, keyed on every library type) and one per type with its own sources, keyed on the type alone (the engine's outgoing walk covers its children); a type's total is held to its `DamageBonusMax` before the split.
- Flats and route lines are not here: zc-entity's `TypedDamageBonusSystem` adds them per hit (flats on a direct hit only).
