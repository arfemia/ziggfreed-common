# zc-objectives

- No library module may depend on this one: it sits above zc-progression and zc-presentation so it can join them, which makes any edge out of it safe and any edge into it a break.
- Consumer databases store the `ZigProgressComponent` blob verbatim, so its wire format is a contract: append new leaves, and keep `ZigProgressBlobCompatTest`'s golden fixture decoding.
- `producer/RankAchievementGatedContent` registers every rank achievement (a `REPUTATION_RANK` criterion naming its Qualifier) as `GatedContent`, which is how the Reputation page lists what a rank's achievement pays: zc-reputation reaches it only through that seam, never by importing this module.
- `dungeon/DungeonObjectives` is the one bridge from the moment stream to zc-instance's dungeon runs: a moment listener that matches a member's moments against their run's `Complete.Objective` the way a quest step reads its leaves and counts through `DungeonRuns.countObjective`, since zc-instance cannot see the stream.
- The book, the NPC page and the title picker paint through zc-presentation's kit (`ui/kit`: `LedgerPainter` rows, `DetailPainter` pages and lines, the tiles), so change a row's, a line's or a page's look in the kit's documents, not in a template of this module's.
