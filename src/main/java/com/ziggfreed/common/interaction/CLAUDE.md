# interaction/ - fire a native RootInteraction chain by id

Router for `com.ziggfreed.common.interaction`. One primitive: composing a consumer's own
authoring vocabulary onto NATIVE Hytale interaction content BY REFERENCE (the id-ref-only
content-composition principle - our schemas reference native asset ids, never inline a native
body), instead of re-deriving motion/VFX/damage that an existing `RootInteraction` chain already
does.

- **[`NativeChainFire`](NativeChainFire.java)** - `static boolean fire(Store<EntityStore> store,
  Ref<EntityStore> casterRef, String interactionId, InteractionType interactionType)`. Resolves
  `interactionId` via `RootInteraction.getAssetMap().getAsset(id)` (a direct lookup, NOT the
  engine's own `getRootInteractionIdOrUnknown`, which silently stubs an unknown id to an empty
  0-operation placeholder - this util treats a miss as a hard failure instead) and queues it via
  `InteractionManager.initChain(type, context, root, false)` + `queueExecuteChain(chain)` - the
  proven server-side chain-trigger mechanism (ledger: `hytale-interaction-trigger` /
  `RootInteraction.getAssetStore()` is ONE global, unscoped-by-owner store, so ANY registered id -
  vanilla, pack, or plugin - is fireable by any caller with zero ownership check).
- **Fail-closed, one guarded log per failure path**: no `InteractionManager` component on the
  entity (guarded FINE), an unresolved `interactionId` (guarded WARN), or any engine throw
  (guarded WARN) all degrade to `false` - never propagate, never fall through to `initChain` with
  an unresolved root.
- **`forceRemoteSync=false` is NOT a guarantee the chain stays server-only** - `initChain` ORs the
  caller's argument with the root's OWN `needsRemoteSync()`, so a chain containing a client-package
  op still syncs to the owning client's game state even when this util always passes `false`. A
  real player's client must be able to execute the same root (desync risk if the chain needs
  client ops the caller's client doesn't expect); an entity-less/NPC caller auto-runs
  `simulationTick` server-side instead, no real client needed.
- **World-thread only** (reads/mutates the entity's `InteractionManager` component); the caller
  guarantees the thread. Lifted config-free out of the same mechanism a consumer mod's own
  `NativeChainFire` already proved in production (the seam-wave lift target for that consumer's
  step handlers to re-point onto, per the decision-51 composition-gate ruling); a consumer keeps
  only its own `InteractionType` resolution / id vocabulary policy on top of `fire`.
