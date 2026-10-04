# sound/

- Call from the world thread: the asset-map and transform reads are not thread-safe (the packet write is).
- Sound indices resolve through `util.AssetIndexCache`, which caches only a positive index, so a missing or late-loading pack re-resolves on the next call instead of going silent for good.
- `Sound3D.playOn` makes a sound follow an entity and sends it only to that entity's current viewers; the engine's `SoundUtil.playSoundEventEntity` broadcasts to the whole world, so never use it. `playOn` answers 0 for an entity no tracker shows yet (spawned this tick): fall back to `playAt`.
- A server-side state flip (`BlockOps.setInteractionState`, `BlockOperations.setBlockInteractionState`) never plays the state's `InteractionSoundEventId`: call `BlockStateSound.playInteractionSound` after flipping it. It reads the block off its loaded chunk section (zc-core's `world/SectionBlockCursor`), so a block whose section is not in memory plays nothing.
