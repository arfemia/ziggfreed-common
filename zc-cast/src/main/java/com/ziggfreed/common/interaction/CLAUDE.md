# interaction/

- Compose native interaction content by id through `NativeChainFire`; a consumer keeps only its own `InteractionType` and id policy on top.
- Resolve a root with `RootInteraction.getAssetMap().getAsset(id)`, never `getRootInteractionIdOrUnknown`, which stubs an unknown id to an empty chain and only logs.
- `forceRemoteSync=false` does not keep a chain server-only: the chain ORs it with the root's `needsRemoteSync()`. Pass `true` only when you know a client-run node hides behind a `Serial` wrapper or a `RunRootInteraction`, which that shallow scan misses.
- Walk a chain through `ChainWalker`, never the engine walk alone: the engine skips several child slots (`EngineWalkGaps`), has no cycle or depth guard, and its `Collector` cannot prune one branch (stopping ends the whole walk).
- `NativeInputGate` answers not-ready on any uncertainty, so pair it with a caller that fires the root anyway when its own alternative declines.
