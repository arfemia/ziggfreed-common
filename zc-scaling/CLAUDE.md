# CLAUDE.md - zc-scaling

Pure difficulty-scaling math: fold participant powers, clamp to a band. No engine types at all,
which is why it stands alone rather than folding into `zc-core` (nothing else here needs a second
module to hold one small, genuinely standalone algorithm).

## Build

Part of the thirteen-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-scaling`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: nothing (not even `zc-core`) - see the `build.gradle` comment: "no engine types
  at all". Pure math over primitives.
- **Depended on by**: no other library module. Consumed directly by the sibling `mmo-mob-scaling`
  mod at the aggregate-jar level, which is the whole reason this exists as a domain-free primitive
  rather than mob-scaling policy baked into a domain module.
- **Reverse-edge trap**: an edge FROM here to anything, even `zc-core`, is the first sign this
  module has stopped being pure math. If a future change needs logging or a factor read, that is a
  sign the change belongs in the consumer, not here.

## Packages

- [`scaling/`](src/main/java/com/ziggfreed/common/scaling/CLAUDE.md) - `ScalingContext` (base
  difficulty + a `double[]` of participant powers + `AggregationMode` + an opaque instance handle),
  `PowerAggregation` (the fold), `ScalingEngine.resolve(ctx, bandWidth, minCap, maxCap)` (the
  band-clamped effective difficulty). The ONE engine open-world mob scaling and any future instance
  scaling both call; only the context inputs differ between them.

## Shipped resources

None. Pure logic module, no `Server/` or `Common/UI/` content.

## Conventions

Stays domain-free and engine-free by construction; a change that would need to know what a "mob" or
a "player" is belongs in the consumer's own scaling policy, not in `ScalingContext`'s opaque
instance handle.

## Tests

2 files: `PowerAggregationTest` (the fold math), `ScalingEngineTest` (resolve + band clamp).
