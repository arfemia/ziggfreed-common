# objectives/marker/ - the library's quest marks

- The library draws every quest mark itself: `QuestMarkers` recomputes one viewer at a time (the six quest events, player ready, a 5 s sweep; never a tick) and fires `ZigQuestMarksEvaluatedEvent` carrying a `QuestMarkerScope`; each surface listens on the bus through `QuestMarkers.registerSurface` (that event, and `PlayerDisconnectEvent` for its forget), never the deprecated `addListener`. A consumer never draws a second set.
- Hosts are placed characters only (`PlacedNpcComponent`). A listener runs on the world thread: spawn through `scope.accessor()` (the tick's command buffer during the sweep), read through `scope.store()`.
- `QuestOverheads` asks the NPC quest page's own answer set (`NpcQuestPages.resolvedDeps().answerSetOrOwn`), so the mark and the page agree.
