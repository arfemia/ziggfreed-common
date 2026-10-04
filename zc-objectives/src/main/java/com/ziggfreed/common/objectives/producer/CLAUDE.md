# objectives/producer/ - the always-on native-event producers

- `ProgressDispatch.fire` hands every moment to all `MomentListener`s before the subject test and the system gates, so a player with no subject and a server with both systems off still get every reaction. The alias route (`fire(..., DispatchOptions)`) reaches the engines only, never the listeners, or a reaction would pay twice.
- There is deliberately no "is anything listening" short-circuit before the engines: the observer tap and the listeners must see moments no content wants (a lifetime counter counts every break).
- The 6-argument `fire` is the stable entry point for a net-new moment. The producers use the payload overload, and `MomentPayload` is an open marker a fourth party implements on equal terms.
- `ZigMobKillProducer` asks the composed `KillAttribution` (for a non-player attacker) and `KillQualifier` once, at fire time; the first real answer wins. There is no second qualified re-fire: an unqualified criterion already matches every kill, so a re-fire would count one kill twice.
- The break and pickup producers skip what the player placed (`world/placed/PlacedBlockLedger`). The place producer counts through `PlacedBlockRecorder.placementCounts`, because `PlaceBlockEvent` fires before the engine refuses a placement.
- `STAT_THRESHOLD` has no producer and never will: it names a state the engines read themselves.
- The craft producer's query stays unfiltered (`Archetype.empty()`) and targets the crafted output item's id. A workstation craft does not count, because `CraftRecipeEvent.Post` names no crafter: fix it the day the engine exposes one, never by reflection.
- The bus producers (`ZigInstanceRoundProducer`, `ZigEncounterProducer`, `ZigLootReceivedProducer`) resolve each player's own world and hop there through `PlayerMomentDispatch`.
- `ZigLootReceivedProducer` dispatches through `PlayerMomentDispatch.fireDeferred`, never inline: `LootReceivedEvent` fires from inside an engine payout, and an inline dispatch would re-enter the engine mid-payout.
