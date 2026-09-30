# dialogue/quest/ - quest-aware lines and the completion hand-off

- Only `QuestCompletionRouting` decides whether a completion conversation plays. A surface with nobody in front of the player skips it (`NO_NPC_CONTEXT`); a UI registers a `QuestDialogueHost` (`knows` and `open` on one interface) rather than deciding.
- The routing does not re-check that the quest settled: the caller fires it at the moment it owns.
- `DialogueQuests.accept` takes the site id: pass the conversation's own context id, never an answered alias.
- Both write methods refuse by default, and `DialogueQuests.NONE` reads `NOT_STARTED` and refuses `canCompleteAt`.
- `HasOfferableQuests` is not seeded: a `Type` id is process-wide, so a consumer registers `offerableType(quests)` in the same change that drops its own condition.
- Override `subjectOf(store, ref, player)` when your runtime needs a richer subject; `subject(ctx)` is the render path and never throws.
- An option whose actions park a quest routes the player to the character's quest list with that quest highlighted, overriding the option's own `Goto`, `Close` and `Open`.
