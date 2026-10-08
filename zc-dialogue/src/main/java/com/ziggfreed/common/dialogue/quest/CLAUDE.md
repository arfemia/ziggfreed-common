# dialogue/quest/ - quest-aware lines and the completion hand-off

- Only `QuestCompletionRouting` decides whether a completion conversation plays. A surface with nobody in front of the player skips it (`NO_NPC_CONTEXT`); a UI registers a `QuestDialogueHost` (`knows` and `open` on one interface) rather than deciding. `QuestDialogueHosts` asks the library's own page (`LibraryDialogueHost`, every conversation in the shared store) after every registered host, so a consumer registers a host only for a screen of its own, never to open the library's page.
- The routing does not re-check that the quest settled: the caller fires it at the moment it owns.
- `DialogueQuests.accept` takes the site id: pass the conversation's own context id, never an answered alias.
- Both write methods refuse by default, and `DialogueQuests.NONE` reads `NOT_STARTED` and refuses `canCompleteAt`. `NONE` answers only where neither a consumer nor the library default is installed (the shared engine says so once).
- `HasOfferableQuests` is seeded with the rest (`QuestDialogueConditions.types`); a consumer registers nothing for it, and a second registration is refused.
- Override `subjectOf(store, ref, player)` when your runtime needs a richer subject; `subject(ctx)` is the render path and never throws.
- An option whose actions park a quest routes the player to the character's quest list with that quest highlighted, overriding the option's own `Goto`, `Close` and `Open`.
