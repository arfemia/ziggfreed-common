# sound/ - shared 3D SoundEvent playback

Router for `sound/`. World-thread only (asset-map + `TransformComponent` reads); every entry point swallows missing-asset / runtime errors so a misconfigured sound never throws into the caller.

- **[`Sound3D`](Sound3D.java) is the single 3D sound seam** - the superset of the MMO `AbilitySoundUtil` (play-by-id at a position, warn-on-missing toggle) and the Kweebec `HeartbeatService.playSoundEvent3d` (a `SoundCategory` + a per-listener `Predicate<Ref>` for a private sound). `play(...)` at a `Vector3d` or explicit coords; `playAt(...)` at an entity's `TransformComponent` position. `onlyEntity(ref)` is the self-only predicate for a per-player heartbeat / stinger; `DEFAULT_CATEGORY = SFX`.
- **Index resolution goes through [`AssetIndexCache`](../util/AssetIndexCache.java)** (one cache per id, lazily created in a `ConcurrentHashMap`), so an id that is missing or whose pack is not loaded yet re-resolves next call instead of latching into silence - it caches ONLY a strictly-positive index.
- **World-thread**: call inside `world.execute` (or already on the world tick). The packet write itself is thread-safe, but the asset-map and transform reads are not.
