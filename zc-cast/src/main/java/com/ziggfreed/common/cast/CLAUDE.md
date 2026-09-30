# cast/

- `cast/` and `cast/step/` are additive-only: every class here is reached from a shipped sibling mod, so never rename, remove or narrow a signature; new composition goes in `interaction/`.
- The library fires the world add and remove fan-out itself (`npc/NpcBootstrap.registerWorldLifecycle`), and `WorldEvictors` skips a repeated removal, so a consumer only registers evictors.
- `AbstractWorldFrameSystem` rides the engine's query-less `TickingSystem`, which `Store.tickInternal` ticks once per world store (`hytale-shared-source/HytaleServer/Component/src/main/java/com/hypixel/hytale/component/Store.java`), so a drain needs no dedup gate.
