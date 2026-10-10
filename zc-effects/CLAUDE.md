# zc-effects

- zc-effects and zc-loot never import each other: a reward that grants an effect is the `Effect` reward kind the wiring root registers (`reward/EffectRewardKind`).
- `NativeEffectUtil.has` answers false whenever it cannot tell, so a caller reconciling a wanted effect applies on false. `applyInfinite` is the no-expiry overload for an entity with no `EntityStatMap` (a puppet or prop, where the engine's effect timer never runs): pair it with `remove`.
- An unresolved effect id is named at WARN once per id per process (`warnUnresolvedOnce`, from `apply` / `applyFor` / `remove`) and at FINE after that, so a caller reconciling on every change never repeats the line.
- `AppliedEffectTracker.removeAll` clears unconditionally, and a round or encounter `stop()` relies on that.
- `effect/costume/` is the costume a player can always take off. A costume is an effect that changes the model and is not a `Debuff` (`CostumeRules`); `ZigCostume` puts one on the player a chain targets and tells them `/zigcostume off` takes it off, and taking off goes by that rule, never by a record. A different costume is refused while one is worn: the engine keeps the model from before the first model change to restore, and a swap inside one tick would record the first costume's model instead. zc-cast is this module's one other library edge, for that Type.
