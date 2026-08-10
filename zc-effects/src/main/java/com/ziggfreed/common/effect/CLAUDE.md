# effect/ - apply/remove a native EntityEffect by id, plus session tracking

Router for `com.ziggfreed.common.effect`. The plain id-in/id-out primitive for composing native
Hytale `EntityEffect` content BY REFERENCE (station/moment presentations, dialogue actions, a
`Grants.Effects[]` at cast/session level) - a SIBLING to `instance/effect/EntityEffectService`
(that package is the Kweebec-style encounter/pace-band framework, with its own
`applyTimed`/`applyBand` shapes over an `EffectBandLadder`); this package has no ladder/band
concept, just apply, apply-with-override, remove, and "track what a session applied so it can all
come off at stop()". Both packages are legitimate - pick by shape needed, do not merge them.

- **[`NativeEffectUtil`](NativeEffectUtil.java)** - static, ref-scoped:
  - `apply(Store|ComponentAccessor, ref, effectId)` - the ASSET-AUTHORITATIVE 3-arg engine
    overload (`EffectControllerComponent.addEffect(ref, entityEffect, accessor)`): duration,
    `OverlapBehavior`, and the infinite flag all come from the effect ASSET itself.
  - `applyFor(Store|ComponentAccessor, ref, effectId, durationSeconds, overlap)` - the
    DURATION-OVERRIDE 5-arg overload (`addEffect(ref, entityEffect, duration, overlapBehavior,
    accessor)`): reuses one effect asset's other fields (tint/particles/movement-lock/
    ability-disable) at a CALLER-chosen duration + overlap, so a session/step can pick its own
    lifetime without a duplicate effect asset per duration (ledger: the `EntityEffect`
    Root/Slow/Freeze/Stun mine - vanilla ships `Stick_Stun` Duration2 vs `Bomb_Explode_Stun`
    Duration5 as two SEPARATE assets from JSON; this 5-arg Java overload is the wider door that
    avoids that duplication).
  - `remove(Store|ComponentAccessor, ref, effectId)` - resolves `effectId` to its current engine
    index (`EntityEffect.getAssetMap().getIndex`) and calls `removeEffect`; safe to call on an
    effect that is not currently applied (the engine no-ops on an unresolved index, and this util
    additionally fails closed on a bad ref / missing controller / unresolved id / engine throw).
  - **Fail-closed throughout, one guarded log per miss** (FINE for a null/invalid ref or a missing
    `EffectControllerComponent`, WARN for an unresolved id or an engine throw) - never a throw
    into the caller, never a silent success.
- **[`AppliedEffectTracker`](AppliedEffectTracker.java)** - the companion "remove everything a
  session applied" bookkeeping primitive: `track(ref, effectId)` records a `(ref, effectId)` pair
  - call it ONLY after the matching `apply`/`applyFor` returned `true` (the tracker never verifies
  or re-applies anything itself); `removeAll(Store|ComponentAccessor)` walks every tracked entry
  through `NativeEffectUtil.remove`, best-effort PER ENTRY (one failed remove never stops the
  rest), then clears the list UNCONDITIONALLY so a later `removeAll` never re-attempts a stale
  entry. `isEmpty()`/`size()` for callers that want to skip an empty teardown. Plain mutable list,
  not thread-safe - one instance per session, touched only from the world thread that owns it
  (mirrors `instance/effect/EntityEffectService`'s own "caller tracks state, service holds none"
  split). Supports multiple different target refs in one session (each tracked entry carries its
  own ref); nothing assumes a single fixed target.
- **World-thread only** throughout (reads/mutates an `EffectControllerComponent`); the caller
  guarantees the thread. No content, no ids baked in - a consumer supplies every effect id.
