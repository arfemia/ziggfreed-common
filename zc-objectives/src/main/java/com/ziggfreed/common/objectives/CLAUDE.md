# objectives/ - the library's own parts of the shared progression runtime

- There is one `QuestEngine` plus `AchievementEngine` per server, held by zc-progression's `progress/runtime`. `runtime/ProgressionDefaults` registers this library's parts into it at library-default rank, so any consumer registration outranks them.
- The progress component type and the producer systems register at `setup()`, and the connect-time attach is unconditional (the component also holds dialogue memories). `ProgressionRuntime.usesDefaultStores()` gates only the player-ready maintenance pass.
- Never register a competing producer for an event `producer/` covers. A net-new moment registers its kind and calls `ProgressDispatch.fire`; a consumer reacts to a produced moment through a `MomentListener`, never a second ECS system on the same native event.
- Both halves of a hand-in (`QuestPossessionProbe`, `QuestInventoryConsumer`) and a `ProgressHandle` that answers for `Player` and `PlayerRef` are mandatory: without them hand-ins and rewards silently do nothing while every surface reports success.
- There is no reward retry queue here: a reward that fails to grant is logged and lost.
- Every surface (the book, the NPC quest page, the tracked HUD) reads the runtime's own subject and wraps each mutating call in the registered `ProgressionCallScope`; a subject built locally reads neutral through another mod's store and drops every write.
- A claim toast lists the receipt (`tryClaim`'s `GrantOutcome.receipt()`, painted by `render/ClaimToasts`), never the authored reward list.
- The progression admin page opens only through the static `admin/ProgressionAdminPages.open`, never a registered destination, and its audience defaults to deny.
