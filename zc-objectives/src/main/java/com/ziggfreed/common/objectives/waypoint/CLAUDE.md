# objectives/waypoint/ - the quest marks on the compass and map

- The library points every player at their tracked quests' destinations and marks the characters with a quest on offer (`QuestWaypoints`, provider `ziggfreedcommon:quest_markers`, a `QuestMarkers` surface). A consumer never registers a second quest pointer or quest map mark, or each character carries two.
- A destination is the current step's first objective, in step order, the pointer can place: a hand-in lock or a place-targeted target naming a placed character (`PlacementWorlds.wheresOf` answers it). One naming a location, a board or an id nothing places is passed over (R61, `QuestWaypointTargets.destinationOf`).
- A character standing under a `Where` this world matches is pointed at directly. One standing only in other worlds is pointed at through the NEAREST gateway here leading into one of them (`gateway/<id>` keys), and not at all when none does. A quest-on-offer mark never routes through a gateway. Nothing here opens or gates a world.
- While a consumer still draws its own marks (`indicator/QuestMarkYield`) only the `gateway/` pointers stay. Read `QuestIndicators.mapMarks`, never the deprecated `mapMarksFor`.
