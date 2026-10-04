# objectives/book/ - the objective book

- `canDeliverTurnInAt(subject, quest, null)` is always false, so never gate the Hand in button on it: the book uses `firstActiveTurnIn(subject, quest, null)` and then `attemptTurnIn`, which re-checks everything. A hand-in locked to a character can never complete from the book.
- A giver-bound quest (`BookQuestsTab.giverBound`) is never accepted from the book; `QuestEngine.canAccept` stays open, because the NPC quest page is where that quest is taken.
- Any String-only sink (an item slot's hover name, a dropdown label, the search haystacks, a sort key) goes through `UiText.flatten`; everything else stays a client-resolved `Message` on `.TextSpans`.
- A partial update keeps the scroll: clear the repainted hosts, re-append, bind only the fresh elements in the update's own event builder, then `sendUpdate(cmd, events, false)`. Reopen only where a partial cannot tell the truth (a filter, search, sort or tab change, or a change that re-ranks every row).
- The achievement tab lists through `AchievementShelves`: an earned achievement is listed whatever its circulation (its feat flag picks the shelf), and circulation and `isVisible` gate only what the player has not earned. Never filter on `available()` before asking whether it was earned.
