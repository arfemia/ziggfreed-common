# cast/

- `cast/` and `cast/step/` are additive-only: every class here is reached from a shipped sibling mod, so never rename, remove or narrow a signature; new composition goes in `interaction/`.
- The library fires the world add and remove fan-out itself (`npc/NpcBootstrap.registerWorldLifecycle`), and `WorldEvictors` skips a repeated removal, so a consumer only registers evictors.
- `ModelParticleService.spawnOn` attaches particles to an entity and delivers only to the players whose tracker shows it (`entity/EntityViewers`), returning how many got it: zero means fall back to a positional `spawnAt`. That route has no playback cap, so attach only a system `ParticleLifetimes.systemProvablyEnds` accepts (a positive `LifeSpan`); keep an endless one world-positioned.
- `AbstractWorldFrameSystem` rides the engine's query-less `TickingSystem`, which `Store.tickInternal` ticks once per world store (`hytale-shared-source/HytaleServer/Component/src/main/java/com/hypixel/hytale/component/Store.java`), so a drain needs no dedup gate.
