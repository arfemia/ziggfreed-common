# interaction/ - compose native Hytale interaction content BY REFERENCE

Router for `com.ziggfreed.common.interaction`. The charter: compose a consumer's own authoring
vocabulary onto NATIVE Hytale interaction content BY REFERENCE (the id-ref-only content-composition
principle - our schemas reference native asset ids, never inline a native body), instead of
re-deriving motion/VFX/damage that an existing `RootInteraction` chain already does. This root
holds chain FIRE + chain WALK; three sub-packages extend the same charter with their own concerns
(each has its own `CLAUDE.md`):

- **[`type/`](type/CLAUDE.md)** - the clinit-safe custom-`Interaction`-Type registration toolkit
  (`InteractionTypeSpec`/`InteractionTypes`) plus the shared `firstRun` accessor/outcome
  conventions (`InteractionCtx`/`InteractionBody`/`InteractionOutcome`) every custom Type reuses.
- **[`param/`](param/CLAUDE.md)** - the fire-time parameter seam: `CastScope` (the per-fire
  payload) + `CastScopes` (the context-meta door that stashes/reads it, and survives every Selector
  hit fork) + the `ParamFold`/`ParamFoldResolver`/`ParamSlot` per-caster number-fold SPI.
- **[`target/`](target/CLAUDE.md)** - the server-authoritative targeting engine (`TargetQuery`/
  `TargetSweep`/`TargetHit`/`LineOfSight`) that replaces the native, Client-trusting
  `SelectInteraction` sweep for anything that must not trust the client.

## Root: chain fire + chain walk

- **[`NativeChainFire`](NativeChainFire.java)** - `static boolean fire(Store<EntityStore> store,
  Ref<EntityStore> casterRef, String interactionId, InteractionType interactionType)`. Resolves
  `interactionId` via `RootInteraction.getAssetMap().getAsset(id)` (a direct lookup, NOT the
  engine's own `getRootInteractionIdOrUnknown`, which silently stubs an unknown id to an empty
  0-operation placeholder - this util treats a miss as a hard failure instead) and queues it via
  `InteractionManager.initChain(type, context, root, false)` + `queueExecuteChain(chain)` - the
  proven server-side chain-trigger mechanism (ledger: `hytale-interaction-trigger` /
  `RootInteraction.getAssetStore()` is ONE global, unscoped-by-owner store, so ANY registered id -
  vanilla, pack, or plugin - is fireable by any caller with zero ownership check).
  - **Decorator overload**: `fire(store, casterRef, interactionId, interactionType,
    Consumer<InteractionContext> decorator)`. The decorator runs AFTER `initChain` and BEFORE
    `queueExecuteChain` - the per-fire context door. Pair it with `param.CastScopes.decorator(scope)`
    to stash a `CastScope` that every node in the chain (including every Selector hit fork, since a
    fork is a `context.duplicate()` that copies the meta store) can read back. A throwing decorator
    is caught, logged once (guarded WARN), and the chain STILL FIRES - a broken decoration never
    swallows a cast. The 4-arg form is unchanged (delegates with a null decorator).
  - **`forceRemoteSync` overload**: `fire(store, casterRef, interactionId, interactionType,
    decorator, boolean forceRemoteSync)` - the flag goes straight to `initChain`, which ORs it with
    the root's own `needsRemoteSync()` and freezes the result as the chain's `requiresClient`. The
    engine's own derivation is SHALLOW (an OR over the root's TOP-LEVEL nodes, recursing only
    through a `Simple`'s `Next`/`Failed`), so it misses a client node inlined by a `Serial` wrapper
    or reached through a `RunRootInteraction` ref - such a chain stalls the server on client data
    nobody was asked for and is cancelled at the ping-scaled timeout. Pass `true` when the CALLER
    knows the chain contains a client-run node; the three shorter overloads all pass `false` and
    are unchanged. Forcing it on a genuinely server-only chain costs a sync packet and makes the
    client run a chain it cannot contribute to, so it is a declaration, never a default.
- **Fail-closed, one guarded log per failure path**: no `InteractionManager` component on the
  entity (guarded FINE), an unresolved `interactionId` (guarded WARN), or any engine throw
  (guarded WARN) all degrade to `false` - never propagate, never fall through to `initChain` with
  an unresolved root.
- **`forceRemoteSync=false` is NOT a guarantee the chain stays server-only** - `initChain` ORs the
  caller's argument with the root's OWN `needsRemoteSync()`, so a chain containing a client-package
  op still syncs to the owning client's game state even when the caller passes `false`. A
  real player's client must be able to execute the same root (desync risk if the chain needs
  client ops the caller's client doesn't expect); an entity-less/NPC caller auto-runs
  `simulationTick` server-side instead, no real client needed. The REVERSE case (a client node the
  engine's shallow scan cannot see) is what the `forceRemoteSync` overload above is for.
- **World-thread only** (reads/mutates the entity's `InteractionManager` component); the caller
  guarantees the thread. Lifted config-free out of the same mechanism a consumer mod's own
  `NativeChainFire` already proved in production (the seam-wave lift target for that consumer's
  step handlers to re-point onto, per the decision-51 composition-gate ruling); a consumer keeps
  only its own `InteractionType` resolution / id vocabulary policy on top of `fire`.

- **[`ChainWalker`](ChainWalker.java)** + **[`ChainWalk`](ChainWalk.java)** +
  **[`ChainNode`](ChainNode.java)** - transitive, CYCLE-GUARDED, fail-soft resolution of a
  `RootInteraction` chain into its reachable nodes, for validators and description renderers.
  `ChainWalker.walk(rootInteractionId, type[, maxDepth, maxNodes[, context]])` rides the engine's
  own `InteractionManager.walkChain(Collector, InteractionType, InteractionContext,
  RootInteraction)` with a guarding `Collector`, so it follows each concrete node's OWN declared
  child refs (including fork edges like `SelectInteraction`'s `HitEntity`/`HitEntityRules`) -
  strictly more than the flat compiled `getOperation(i)` view the MMO's own
  `ability.NativeChainAudit` uses (fork-blind prior art this generalizes past).
  - **Two engine limitations, surfaced not hidden.** (1) The engine `Collector` contract has no
    "prune this branch" return - a `collect` returning `true` (a cycle or an exceeded cap) ENDS THE
    ENTIRE WALK, never just the offending branch; the result (`ChainWalk`) carries the flag plus
    everything collected so far. (2) `walkInteraction` THROWS `IllegalArgumentException("Failed to
    find interaction: <id>")` on an unresolvable child id rather than skipping it; `ChainWalker`
    catches that and reports it as `ChainWalk.aborted()` + `abortReason()` with the engine message.
  - **Cycle detection is IDENTITY-based on the current path** (an `ArrayList<Interaction>` mirrored
    by `into`/`outof`, including the root's null frame), sound because interaction assets are
    decode-once immutable singletons shared by every firing entity.
  - **The engine ships NO cycle or depth guard anywhere** (`compile()` recurses raw, the chain tick
    loop has no cap) - a self-referencing fragment stack-overflows the server at load. `ChainWalker`
    is the prerequisite guard for any public fragment library, defaulting to
    `DEFAULT_MAX_DEPTH = 32` / `DEFAULT_MAX_NODES = 512`.
  - Guard order: a null/blank `rootInteractionId` or a null `InteractionType` returns
    `rootResolved(false)` with ZERO engine touch. Resolution is via the DIRECT
    `RootInteraction.getAssetMap().getAsset(id)` lookup (never `getRootInteractionIdOrUnknown`,
    which silently stubs an unknown id). Default context is `InteractionContext.withoutEntity()` -
    an entity-less walk cannot resolve item `InteractionVars`, so a `Replace` slot may take its
    default branch; pass a live context when that matters.
  - **Supplemental blind-slot pass ([`EngineWalkGaps`](EngineWalkGaps.java), 2026-08-03).** The
    engine's own `interaction.walk()` coverage MISSES several child-bearing codec slots (a Type
    adding a child field without overriding `walk()` inherits `SimpleInteraction.walk`'s
    Next/Failed-only visit): `ApplyForce.GroundNext`/`CollisionNext`, `Charging.Forks` (+
    inherited by `Wielding`, whose own `BlockedInteractions` is also blind),
    `MovementCondition`'s 8 direction slots, `Chaining.Flags`, `RunOnBlockTypes.Interactions`,
    and `RunRootInteraction`'s ENTIRE payload (which is also invisible to the engine's static
    `needsRemoteSync()` scan - the requiresClient landmine). After the engine walk,
    `ChainWalker` re-walks every collected node's gap slots through the same guarding collector
    (reflective field reads, dual Interaction/RootInteraction id resolution, identity-set
    once-per-node cap so gap-slot cycles terminate). Source-verified against shared source
    `6cdea5ead`; a reflective miss on an engine update degrades to skip + one WARN per class.
