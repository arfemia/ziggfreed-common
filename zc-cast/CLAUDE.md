# CLAUDE.md - zc-cast

The cast/interaction runtime: the generic step-dispatch kernel, hit resolution, armed state, ray
and block targeting, the per-world tick partition, and the native interaction-composition
framework. A consumer that fires abilities or authors native interaction chains builds on this
module; it ships EMPTY of content (no baked step vocabulary, no consumer-specific policy).

## Build

Part of the twelve-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-cast`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core` only (for `CommonLog`, `util`, and the `cast.WorldEvictors` split
  package it composes with via FQN, not an import of the rest of this module's runtime).
- **Depended on by**: `zc-objectives` only.
- **Reverse-edge trap**: this module is a LEAF by design - no other library module depends on it,
  which is correct, because it is a consumer-facing runtime layer, not framework plumbing that a
  domain module would rest on. The one piece every domain DOES need, the per-world eviction
  fan-out, was deliberately split out to `zc-core` as `cast.WorldEvictors` rather than dragging the
  whole 38-class runtime onto three unrelated modules for one class.

## Packages

- [`cast/`](src/main/java/com/ziggfreed/common/cast/CLAUDE.md) (+ `cast/step/`) - the generic
  step-dispatch kernel (`StepHandler`/`StepRegistry`/`StepSemantics`/`CastKernel`, parameterized
  over a consumer's own context/step/result types), `OnHitRegistry`, `HitContext`/`HitAction`/
  `HitResolver`, `ObserverRegistry`, `CastParams`, `ArmedStateStore`, `RaycastTargeting`/
  `BlockRaystep`, the per-world tick partition (`WorldFrameGate`/`WorldKeyedQueues`/
  `AbstractWorldFrameSystem` - `WorldEvictors` itself is the zc-core split-package file this module
  composes with by FQN), `ModelParticleService`.
- [`interaction/`](src/main/java/com/ziggfreed/common/interaction/CLAUDE.md) - the generic
  interaction-composition framework: `NativeChainFire` (fire a named native `RootInteraction`
  chain by id) + `ChainWalker`/`ChainWalk`/`ChainNode` (cycle-guarded chain resolution).
  - [`interaction/param/`](src/main/java/com/ziggfreed/common/interaction/param/CLAUDE.md) - the
    fire-time parameter-fold seam (`CastScope`/`CastScopes`/`ParamFold`/`ParamSlot`).
  - [`interaction/target/`](src/main/java/com/ziggfreed/common/interaction/target/CLAUDE.md) - the
    server-authoritative targeting engine (`TargetQuery`/`TargetSweep`/`LineOfSight`).
  - [`interaction/type/`](src/main/java/com/ziggfreed/common/interaction/type/CLAUDE.md) - custom
    interaction-Type registration plumbing (`InteractionTypeSpec`/`InteractionTypes`/
    `InteractionCtx`/`InteractionOutcome`).

## Shipped resources

None. This module carries no `Server/` or `Common/UI/` content of its own; it is pure runtime.

## Conventions

Everything here is additive-only once frozen (this is a foundational runtime layer other modules'
content and a consuming mod's own systems build against). World-thread only wherever a `Store`/
`Ref` is touched; the kernel and targeting math are otherwise pure/thread-safe. A consumer supplies
its own concrete step/context/result types, on-hit builders, and drain subclass; nothing here
bakes in a particular ability or interaction vocabulary.

## Tests

26 files, the largest test suite relative to package count in the library: the kernel
(`CastKernelTest`, `StepRegistryTest`), hit resolution (`HitResolverTest`, `OnHitRegistryTest`,
`ArmedStateStoreTest`), targeting (`TargetQueryTest`, `TargetSweepTest`, `TargetHitTest`,
`LineOfSightTest`), the per-world partition (`WorldFrameGateTest`), chain composition
(`ChainWalkTest`, `ChainWalkerTest`, `ChainNodeTest`, `NativeChainFireTest`), the param-fold seam
(`CastScopeTest`, `CastScopesTest`, `ParamFoldTest`, `ParamFoldRequestTest`, `ParamSlotTest`), and
custom-Type registration (`InteractionTypeSpecTest`, `InteractionTypesTest`, `InteractionCtxTest`,
`InteractionOutcomeTest`).
