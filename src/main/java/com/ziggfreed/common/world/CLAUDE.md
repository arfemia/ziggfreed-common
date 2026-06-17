# world/ - worldgen / terrain query primitives

Router for `com.ziggfreed.common.world`. Mod-agnostic helpers for reading the world.

- **[`SurfaceProbe`](SurfaceProbe.java)** - `topSolidY(world, x, z, fallback)` / `standableY(...)`: scan a column down for the top OPAQUE-solid block (skips air + `Opacity.Transparent` foliage/glass), mirroring the engine's `GeneratedBlockChunk.getHeight` but reading a live `World` via `World.getBlock(int,int,int)`. **World-thread only** (call inside `world.execute`); every read is try-guarded so an unloaded chunk degrades to the caller's fallback. Use it to floor-snap runtime-placed prefabs/entities onto procedural, uneven terrain instead of a hardcoded Y (e.g. Kweebec's `ArenaBuilder` snaps shrine/exit/gate/cave-shaft pastes to the rolling grove surface).
