# objectives/hud/ - the tracked-quest HUD

- `TrackedQuestHud` repaints on the quest engine's six events (`QuestTracked`, `QuestAccepted`, `QuestObjectiveProgressed`, `QuestCompleted`, `QuestClaimed`, `QuestAbandoned`) through zc-presentation's `RepaintCoalescer`, never a tick; a burst paints once, on the player's world thread.
- Its attach rides `PlayerReadyEvent` at LATE priority and is not behind `usesDefaultStores()`: it reads the runtime's own subject, so it shows the right list whoever owns the stores.
- No quest event announces a player hiding the HUD or a world rule changing: the consumer pushes `TrackedQuestHuds.repaint(playerRef)` from those sites, and `repaintAllOnline()` or `refreshPositionForAllOnline(position)` for an owner-wide change.
- While the panel is drawing a quest, an ordinary `Quest_Objective_Progressed` tick for it sends nothing to the corner feed (`TrackedQuestHuds.alreadyShows`, a `FeedbackSurfaces.Reader`); the tick that finishes a step still announces.
- `TrackedQuestPanelRenderer` is not the HUD: it paints the tracked-quests side panel a page embeds, from the same runtime.
