# entity/ - player model, puppet, prop and item-reading primitives

- Off the world thread, read a player's UUID with `PlayerIdentityCache.uuidOf`; null means not known yet, so skip this pass and never fall back to a deprecated accessor.
- `PlayerModelService.restore` rebuilds the model from `PlayerSkinComponent`, never from a cached `Model`.
- `hideByScale` returns the prior scale; null means none existed, so `revealByScale` removes the component rather than writing 1.0.
- Spawn-side methods take a `ComponentAccessor`, so a `CommandBuffer` caller and a `Store` caller share one method; all of them are world-thread only.
- `setWalking`'s first call adds `MovementStatesComponent` (an archetype change): call it where a component add is legal.
- A puppet has no `EntityStatMap`, so a timed native effect never expires on it: apply one with `NativeEffectUtil.applyInfinite` and remove it yourself.
- `ItemReadings` is the one reader of what an item is worth, and `HeldItemUtil`'s worth methods delegate to it; both answer null, never 0, when they cannot tell.
- A named gather type the tool lacks reads null, not 0, for both power and tier. The tier (per-gather-type `ItemToolSpec.Quality`) is a different field from the item's quality.
- `EntityBootstrap` owns the one `EquipStatBridge`, its trigger systems and the overhead systems; a consumer never registers a second set.
