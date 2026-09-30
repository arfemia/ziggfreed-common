# achievement/

- The server's engine is the shared one in `progress/runtime/`; `builder()` is for tests or a genuinely private engine. Publish milestones with `setMilestones`, never by rebuilding the engine, which orphans every cached reference.
- Every path that mutates the store calls `store.markDirty(subject)` inside the method that wrote, pins included (`pin`, `unpin`, `prunePins`).
- Only a collect commits: `tryClaim` and `tryClaimMilestone` (the boolean `claim` forms wrap them) call `store.flush`. Earning, milestones, meta cascades and self-heal never commit, or a login's self-heal would write once per achievement already held (`AchievementEnginePersistenceReportTest`).
- Criterion progress is stored under `<id>#<critKey>` (the authored `Criteria` key), so renaming a key restarts that criterion while reordering moves nobody. Never add a per-read fallback to a bare-id key; an old save's consumer migration re-keys it once.
- `autoRewards` land on earning and `claimRewards` wait to be collected; never merge them into one list plus a flag.
- `Achievement_Unlocked` fires after the auto rewards pay and carries their receipt plus the claim rewards still waiting; `Achievement_Claimed` carries `collected` and the receipt of what that claim paid.
- `available`, `hidden` and `countsTowardTotal` are independent switches; never add an achievement type constant.
- A `STAT_THRESHOLD` criterion is re-read at self-heal only; never piggyback the re-read on a dispatch, which would scan the whole catalogue on every event.
- A gate that throws is a refusal. A refusal keeps the criteria met, so self-heal earns the achievement once the answer changes, and a gate announces a refusal only on `UnlockOccasion.JUST_MET`, never on a standing re-read.
- `serverFirst` is a flag and the gate arbitrates it. A lost race is the `Achievement_Server_First_Lost` moment; a mod wanting more registers a feedback hook, never a listener of its own.
- The fold decides `icon` and `momentArgs`; the engine only carries them into its moments, and its own argument names win a clash.
- Milestones are state recomputed whenever a total changes, not moments: the achievement whose earning crossed a threshold already fires.
