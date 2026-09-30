# entity/performer/

- Every mutating `StationPerformer` method takes a fresh `ComponentAccessor` from the caller's current frame; never capture a `CommandBuffer` across frames, since it is valid only for its own pass.
- Hiding the real player is not on the interface: the caller hides the player (`PlayerPuppetService.hideByScale`) whichever backend it holds.
- Call `setProp` and `playClip` unconditionally; each backend decides what it can do (the NPC backend's prop and clip are unproven in game).
- `NpcRolePerformer` needs an unlocked `Store` for `NPCPlugin.spawnEntity`. Handed only a `CommandBuffer`, it defers the spawn a tick through `world.execute`, and `isAlive()` reads false and `ref()` null meanwhile.
- NPC clips go through `AnimationUtils.playAnimation` directly: `NPCEntity.playAnimation` (in the shared source) drops a clip the model's animation set lacks on any slot but `Action`.
- `getRole()` is null until the NPC's first tick, so `walkTo` retries the marked-target bind on each poll.
- An engage-time spawn passes `.accessor(commandBuffer)` plus `.world(world)`; a boot-time or command-time spawn passes `.liveStore(store)`.
- `PerformerIdentityComponent` is registered once by the library's `EntityBootstrap`; a consumer never registers it and guards on `TYPE != null`.
- `persist=false` (the default) marks a performer `NonSerialized`.
