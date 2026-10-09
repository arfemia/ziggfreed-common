# zc-dialogue

- Read quests only through progression's `QuestStateReader`, never `QuestEngine`, which mutates: a condition that reaches it could accept a quest while rendering a line. Nothing enforces this (`QuestEngine` is public and on this module's classpath), so check every import.
- Only zc-objectives depends on this module. zc-progression, zc-world, zc-presentation and zc-entity sit below it and never import it; anything else that needs dialogue goes through a seam such as `ui/route/Destinations`.
- `Where` is zc-world's shared `WorldSelector` group (`Match`, `GameplayConfig`, `ExcludeMatch`) everywhere here: placements, the `World` condition, and the `Once` and `Memories` scopes. Never add a second world matcher.
- A placement's and an anchor's `Yaw` is DEGREES end to end: `NpcSpawnService.spawnRotation` converts once to the radians the engine reads, and a yaw captured from a live entity (`/npcplace`, the admin page) is written through `NpcPlacementAuthoring.yawDegrees`.
