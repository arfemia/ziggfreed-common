# zc-effects

- zc-effects and zc-loot never import each other: a reward that grants an effect is the `Effect` reward kind the wiring root registers (`reward/EffectRewardKind`).
- `effect/` applies native `EntityEffect` assets by id (`NativeEffectUtil`, `AppliedEffectTracker`); `instance/effect/` is the timed and banded pace framework. Pick by the shape you need.
- `AppliedEffectTracker.removeAll` clears unconditionally, and a round or encounter `stop()` relies on that.
