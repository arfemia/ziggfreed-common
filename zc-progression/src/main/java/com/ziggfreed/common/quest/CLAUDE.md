# quest/

- The engine ships no content and no consumer vocabulary: `QuestModuleAgnosticismTest` fails the build on a consumer word anywhere in this module's `src/main/java` (routers excluded), so examples use `yourmod:`.
- Every engine path that mutates the store calls `store.markDirty(subject)` inside the method itself. `store.flush` happens only on `claim` (always) and on an auto-claim or `forceComplete` payout that delivered something; never add a flush per engine-decided moment.
- The id-keyed `status(subject, questId)` (the `QuestStateReader` face) reads the player's stored record for an id the catalogue does not carry, and `NOT_STARTED` only when there is none: a definition missing right now never erases what the player did. Readiness reads still fail closed on an unknown id.
- The store is the only state and owns id hygiene (`usesReservedDelimiter`). A store whose `recordsCompletions()` answers false leaves `Reset`, `MaxCompletions` and `PerRun` inert.
- A giver listing asks `isOfferable`, never `isVisible`; `isVisible` is the one question for a browsable listing, and a hidden quest is where the two part.
- Ask what a place offers of `NpcOfferProviders`; never widen `QuestStateReader` for it.
- `readyToTurnInAt` marks a destination and `canDeliverTurnInAt` offers a hand-in button. `canCompleteAt` is the one collection-site predicate, and `claim` and `checkCompletion` enforce it.
- `QuestEngine.clearQuest` is the only re-arm and reports through `QuestResets` (`store().clearQuest` is not a shortcut). It keeps the completion record; `wipeQuest` and `wipeAllQuests` are the admin wipe.
- `CompletionRecord` keeps two tallies: `repeatCheck` reads finishes, `quest_completions` reads collections, and a one-shot writes no record.
- `Quest.repeat()` is nullable and its presence is the repeatable flag. A `Reset` window is one length, never a daily or weekly enum; `QuestCadence` is the one bucketing, and `CooldownFrom` is an anchor, not a mode.
- `Repeat.PerRun` is keyed (event, year) on the completion record's `runYear` and `runCount` (`PerRuns`), never by whether now is inside a run; an old record with no run year belongs to the run whose days hold its last finish. Runs only move forward: a record counted for a later run is spent for every earlier one. `Quest.available()` ANDs the event's running switch, and nothing wipes a carried quest when its run ends.
- A carried quest not on offer (`available()` false) is frozen and uncounted: dispatch, hand-ins and threshold re-reads skip it, and neither the pin cap nor `logSlotsUsed` counts it; filter at those reads, never by pruning its pin or progress.
- The quest-log cap counts `logSlotsUsed` (`Quest.occupiesLog`), not `activeCount`. `available` and `maxActive` are live consumer suppliers, and the refusals built on them stay in the engine.
- `STAT_THRESHOLD` steps are re-read on accept, in `selfHeal` and after a dispatch that moved the same quest; never add a poll or a sweep.
- Quest moments go through the feedback hook unconditionally (`nativeEvents` switches only the event bus). `Quest_Completed` and `Quest_Claimed` carry the grant receipt and `Quest_Parked` the promise; each `try*` twin answers the payout, and its boolean, void or int form is a thin wrapper over it.
- `LockReasons` is the one token-to-line mapping every locked surface reads.
- The payout never throws: `RewardGrants` isolates each reward, so read the `GrantOutcome`.
- Prefer widening an existing seam with a default method over a new builder knob.
