# zc-effects

- zc-effects and zc-loot never import each other: a reward that grants an effect is the `Effect` reward kind the wiring root registers (`reward/EffectRewardKind`).
- `effect/` applies native `EntityEffect` assets by id (`NativeEffectUtil`, `AppliedEffectTracker`); `instance/effect/` is the timed and banded pace framework. Pick by the shape you need.
- `NativeEffectUtil.has` answers false whenever it cannot tell, so a caller reconciling a wanted effect applies on false. `applyInfinite` is the no-expiry overload for an entity with no `EntityStatMap` (a puppet or prop, where the engine's effect timer never runs): pair it with `remove`.
- An unresolved effect id is named at WARN once per id per process (`warnUnresolvedOnce`, from `apply` / `applyFor` / `remove`) and at FINE after that, so a caller reconciling on every change never repeats the line.
- `AppliedEffectTracker.removeAll` clears unconditionally, and a round or encounter `stop()` relies on that.
