# interaction/target/ - the server-authoritative targeting engine

Router for `com.ziggfreed.common.interaction.target`. The trustworthy replacement for the
native, client-trusting `SelectInteraction` sweep: resolves a target LIST only. No forking,
no damage, no hostility policy - a consumer's own custom interaction Type owns all three.

## Why the native selector is disqualified

`SelectInteraction.getWaitForDataFrom()` returns `WaitForDataFrom.Client` (verified,
`SelectInteraction.java:220-221`) - the native selector trusts CLIENT-REPORTED hit sets. An
ability's damage targeting must not be client-authoritative, so this package is the
server-side twin. The consumer's own `MmoSelect`-style Type calls `TargetSweep.volume` /
`TargetSweep.ray` to get the list, then does the per-target fork itself using the exact
engine idiom (`SelectInteraction.java:329-364`):

```java
subCtx = context.duplicate();
subCtx.getMetaStore().putMetaObject(Interaction.TARGET_ENTITY, ref);
context.fork(new InteractionChainData(), context.getChain().getType(), subCtx, hitRoot, false);
```

This package never touches `InteractionContext`/`Interaction` at all - it is pure ECS
(`Store`/`Ref`/`TransformComponent`) plus the `cast/` raycast and block-walk substrate, so it
composes with `interaction/type/` and `interaction/param/` without depending on either.

## Orthogonal knobs, no mode - [`TargetQuery`](TargetQuery.java)

There is no shape/mode field on `TargetQuery`. Geometry is chosen by WHICH
[`TargetSweep`](TargetSweep.java) METHOD is called - `volume` vs `ray`, two genuinely
different narrow phases - and every knob composes on top of either:

- a full **sphere** is `coneAngleDegrees == null` (no angular gate) with `verticalExtent == null`;
- a **cone** is `coneAngleDegrees` set (half-angle off `direction`, degrees, clamped `[0,180]`);
- a **cylinder** is `verticalExtent` set (gates `abs(dy)` instead of full 3D distance for the
  min/max distance check - a cylinder's "distance" is horizontal radius, not sphere radius);
- an **arc slice** is `minDistance` + `maxDistance` plus the cone angle.

Every knob is normalized at BUILD time, never at read time (negative distances/inflate clamp to
0, a non-unit direction normalizes, a zero-length direction becomes `null`, `coneAngleDegrees`
clamps to `[0,180]`, duplicate excluded indices de-duplicate) - see the class javadoc for the
full list. `origin()`/`direction()`/`excludedIndices()` all return DEFENSIVE COPIES.

## Two narrow phases, one shared post-filter - [`TargetSweep`](TargetSweep.java)

- **`volume`** - broad phase `Selector.selectNearbyEntities(store, query.origin(),
  query.maxDistance() + query.inflateRadius(), consumer, ownerExclude)`, narrowed per
  candidate by the distance/cone/cylinder gates described above.
- **`ray`** - delegates the narrow phase entirely to `cast.RaycastTargeting.pickPiercing`
  (caster-index exclusion baked in there already), so `query.owner()` and `query.direction()`
  are REQUIRED; either missing returns empty plus a guarded FINE, never a throw.

Both then run the exact SAME post-filter pipeline, in this fixed order: **owner + excluded
indices -> consumer filter -> line of sight -> ordering -> `maxTargets` cap.** It lives in one
private helper (`applyPostFilter`) so the two narrow phases can never drift apart on filter
semantics.

**Deliberate divergence from the engine, recorded here.** `SelectInteraction` caps its target
set with `reservoirSample` (random). This engine sorts by the ordering scalar
(`TargetHit.distance()`) and takes the first N after every other filter has run. Deterministic
capping is what an ability wants (a cleave always hits the CLOSEST N, not a random N) and what
a unit test can assert without a seeded RNG.

**Guard order (unit-test load-bearing).** `volume`/`ray` short-circuit to `List.of()` on a null
store, a null query, or `query.maxDistance() <= 0` BEFORE any engine touch. Every return is an
immutable list; never `null`, never throws (an internal engine throw is caught, logged once via
`SafeLog.warn`, and degrades to empty).

## Line of sight - [`LineOfSight`](LineOfSight.java)

Delegates to `cast.BlockRaystep.clearDistance(world, from, dir, distance, step, 0.0) >=
distance - 1e-6`. Inherits `BlockRaystep`'s convention: a null world, an unloaded chunk, or an
off-map block counts as CLEAR - line of sight never invents an obstruction it cannot see; only
a `BlockMaterial.Solid` block blocks it. **Honest limitation**: this is a block-GRID test with
no per-hitbox detail boxes, unlike the engine's own finer `HorizontalSelector` line-of-sight
provider, which can test against a target's actual collision shape - a shot that clears the
block grid but would have grazed a fine detail box is reported clear here.

## World-thread notes

Everything here reads live components (`TransformComponent` via `Store.getComponent`) and
walks blocks (`World.getBlock` via `BlockRaystep`) - world-thread only, exactly like `cast/`'s
`RaycastTargeting`/`BlockRaystep` substrate it builds on. `TargetSweep` resolves the `World` for
a line-of-sight check via `store.getExternalData().getWorld()`, guarded (`resolveWorld` never
throws, degrades to `null`, which `LineOfSight` in turn treats as clear).

## What this package does NOT do

No forking, no damage dispatch, no hostility/faction policy, no cooldown/cost gate. It answers
exactly one question - "which entities does this query resolve to, right now, server-side" -
and stops. The consumer's Type (in `interaction/type/`) reads the list and decides what happens
to each entry; `interaction/param/`'s `CastScope`/`ParamFold` decide what number that entry
takes damage for. Composing all three is the consumer's job, not this package's.
