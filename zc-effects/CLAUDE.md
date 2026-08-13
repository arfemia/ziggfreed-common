# CLAUDE.md - zc-effects

Native effect application. Two self-contained packages a consumer can want without dragging the
rest of the instance-experience layer along: the plain id-in/id-out native `EntityEffect`
apply/remove/track primitive, and the timed + banded encounter framework built on it.

## Build

Part of the twelve-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-effects`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core` only.
- **Depended on by**: no other library module today. This module is consumed directly by sibling
  mods at the aggregate-jar level (Kweebec's hunter pace-band swap is the exemplar), not by another
  `zc-*` module.
- **Reverse-edge trap**: this module must NEVER gain an edge to `zc-loot`, in either direction. A
  loot grant that applies a native effect dispatches through a registered reward kind the wiring
  root populates (`reward/EffectRewardKind`), which is exactly what keeps a reward that grants an
  effect and an effect module that grants rewards from closing into a cycle. If a change here ever
  seems to need `loot/reward/RewardSpec` or similar, the fix is a new reward-kind registration in
  the wiring root, never an import.

## Packages

- [`effect/`](src/main/java/com/ziggfreed/common/effect/CLAUDE.md) - `NativeEffectUtil` (apply an
  asset-authoritative 3-arg `apply` or a duration-override 5-arg `applyFor`, plus `remove`) +
  `AppliedEffectTracker` (session-scoped tracked-set, `removeAll` strips everything a session
  applied).
- [`instance/effect/`](src/main/java/com/ziggfreed/common/instance/effect/CLAUDE.md) - the
  reusable ENCOUNTER framework's effect half: `EntityEffectService.applyTimed`/`applyBand` +
  `EffectBand`/`EffectBandLadder`, the on-hit slow/debuff/pace-band seam over
  `EffectControllerComponent`. `BandedEffectAsset`/`BandedEffectConfig` are the authoring layer
  (`Server/ZiggfreedCommon/BandedEffects/`, registered by the wiring root's
  `FrameworkAssetRegistrar`).

`effect/` and `instance/effect/` are siblings, not duplicates: `effect/` is the plain apply-remove-
track primitive for composing native effect content by reference (a station's `Presentation.Effect`
or `Grants.Effects[]`), `instance/effect/` is the Kweebec-style timed/banded pace framework with its
own escalation ladder. Pick by shape needed.

## Shipped resources

None directly; `instance/effect/`'s `BandedEffectAsset` is a registered content TYPE
(`Server/ZiggfreedCommon/BandedEffects/`), but this module ships no default content of its own -
the framework asset-store paradigm is defaults-optional, content is consumer pack JSON.

## Conventions

World-thread, fully try-guarded throughout: a missing asset or bad ref degrades to a no-op, never a
throw into the caller. `AppliedEffectTracker.removeAll` clears unconditionally, which is the
"guarantee every effect a session applied is gone at stop()" shape a consumer's round/encounter
cleanup relies on.

## Tests

Thin relative to the package split: `AppliedEffectTrackerTest` and `NativeEffectUtilTest` (2
files). `instance/effect/`'s banded-ladder math and asset codec have no dedicated suite here yet;
the encounter framework's consumer-facing coverage lives in the exemplar mod (Kweebec) instead.
