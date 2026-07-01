# scaling/ - generic difficulty-scaling engine (domain-free)

Router for `com.ziggfreed.common.scaling`. The mod-root PARADIGM applies: this is a generic,
mod-agnostic Hytale primitive (any mod that scales encounter difficulty by participant power - the
MMO's open-world mob scaling, a future dungeon/raid instance - needs it), so it lives HERE and is
consumed, never re-derived per mod. PURE logic, ZERO engine coupling (no `Store`/`Ref`/`Component`
types); freely unit-testable. There is NO player / party / skill concept in this package - a
consumer reduces each participant to a `double` at the call site and hands over the array.

- **[`ScalingContext`](ScalingContext.java)** - the ONE scaling-input record: `baseDifficulty`
  (authored floor) + `participantPowers` (a `double[]`, 1 solo .. N group/instance, already reduced
  by the consumer) + `aggregationMode` + a `@Nullable Object instanceHandle` (null open-world; an
  opaque instance handle when instanced, never inspected here). The array is held BY REFERENCE (not
  defensively copied, to keep the spawn hot path allocation-lean) - do NOT mutate it after
  construction. `aggregatedPower()` folds the powers; `openWorld(base, regionPower, mode)` and
  `solo(base, power)` are the two convenience factories. The compact constructor null-guards the
  array + mode.
- **[`AggregationMode`](AggregationMode.java)** - how a `double[]` folds into one power: `SOLO`
  (first only) / `AVERAGE` (mean, open-world default) / `PEAK` (max, raid-favoring) / `WEIGHTED`
  (power-weighted mean, high powers drive; instance default) / `DISABLED` (no scaling). `fromName`
  parses a config/API string case-insensitively with a fallback (never throws).
- **[`PowerAggregation`](PowerAggregation.java)** - `fold(double[], AggregationMode)`: the pure fold.
  Empty / null array and `DISABLED` fold to `0.0` (identity delta). `WEIGHTED` = `sum(p^2)/sum(p)`
  (equal powers reduce to the mean; non-positive powers contribute no weight; all-non-positive falls
  back to the mean).
- **[`ScalingEngine`](ScalingEngine.java)** - `resolve(ctx, bandWidth, minCap, maxCap)`: the effective
  difficulty = `clamp(base + clamp(aggregatedPower - base, -bandWidth, +bandWidth), minCap, maxCap)`.
  A `DISABLED` mode or empty participant set returns `baseDifficulty` UNTOUCHED (no clamp - the
  authored floor stands). `bandWidth` is expected non-negative (a consumer-side validator enforces it).

## Consumer contract (the MMO mob-scaling seam)

The MMO mob-scaling mod builds a `ScalingContext` per spawn (open-world: a cached per-region power
scalar via `ScalingContext.openWorld`; a future instance: `Party.orderedMembers()` reduced to a
`double[]` with a non-null `instanceHandle`) and calls `ScalingEngine.resolve` with the zone's
`bandWidth`/`minCap`/`maxCap`. The SAME engine serves both legs - only the context inputs differ.
This package intentionally stops at the pure math: power computation, zone floors, rarity/affix
rolls, and classification are all consumer (mod) POLICY built on top.

## Related lift

Phase-0 companions of this package (same ziggfreed-common 1.2.0 lift): the ref-less
[`HealthUtil.scaleMaxHealth(Holder, factor, key)`](../health/HealthUtil.java) overload (raise a
mob's HP MAX inside a pre-add spawn hook) and the [`EntityIdentifierUtil`](../util/EntityIdentifierUtil.java)
`roleName`/`roleIndex` reads (`NPCEntity` role identity, Store/Ref + `Holder` forms).
