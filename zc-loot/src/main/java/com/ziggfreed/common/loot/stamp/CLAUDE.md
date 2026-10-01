# loot/stamp/

- `StackStatsStamper` is the one stamper, registered by the wiring root. Never register a second one or write stats outside `StamperRegistry`: a second format makes every budget check read half the history.
- A stamper's `metadataKeys()` names every stack metadata key its `apply` writes, the engine's `ItemDisplay` included: registering declares them safe to destroy with the item.
- `StampFactors` reads `ziggfreedcommon:item_stamp_points` off the factor context's item through `StamperRegistry.inspect`, never off the item, so the stamper stays the one authority on the format: no Param reads the total, a stat id that stat alone (0 when never stamped), null with no item, and a server with no stamper reads every item as bare. The wiring root claims the id once through `StampFactors.contribute()`, beside the stamper registration.
- A stamped item keeps its stats in its own stack metadata; stamping never creates an item asset.
- A pool's effective entries are `RollPoolConfig.resolve`'s: the winning file's, then every registered `EntrySource`'s in owner id order, with the file's `StampName` and `Quality` kept. Read a pool only through `resolve` or `poolOf(spec)` (`StampCapEngine.candidates`, a stamped reward's identity, a consumer's stamp step), never by re-merging a mod's entries at a call site; `all` and `resolveAuthored` stay the files as written.
- `StampCapEngine` never branches on a stat id. The one special id, `DefaultStatNames.DURABILITY`, is handled at the write, where it raises max durability.
- No `Picks` authored draws zero. An absent `Weight` is ordinary while a written `0` parks the entry. The lowest `Caps.Budgets` entry binds, and every ceiling counts what the item already carries.
- Never conflate `StampPlan.NOTHING` (a legitimate miss) with `DENIED` (the item is full: abort before charging).
- `StampTooltip` is the one item-description writer. It drops the base prose when the item's own description carries markup, because this surface has no markup parser.
- A stat's name resolves an authored `StatDisplays` file, then a registered `StatNamer`, then the client key `client.itemTooltip.stats.<StatId>`, then the id, so a new stat needs no registration to get a name.
- Engine item metadata (`ItemStack.withMetadata` in `hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/inventory/ItemStack.java`): writing an empty document deletes its key, an undecodable value throws on read, and every read decodes again.
