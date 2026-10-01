# loot/reward/ - the reward vocabulary every payout site shares

- The framework's own kind ids are unprefixed (`Item`, `Stamped_Item`, `Droplist`); a consumer's carry its mod prefix (`Mmo_Xp`).
- Nothing here learns a domain: currency, levels and titles are kinds registered by the mod that owns the concept.
- A handler throws when a reward went nowhere or cannot name what it pays (a quiet return reports it paid), and writes `retryCommand` whenever the reward is replayable.
- A handler reports on the receipt only what reached the player, after handing it over. A toast after a payout lists `GrantOutcome.receiptOf(paid)`, never the authored list; only `Quest_Parked` lists the authored rewards.
- `canAdd` answers about one reward: check a list with `canAddAll`, never `canAdd` in a loop.
- Prefer the `Item` kind to a `Command` running `/give` (it is fit-checked and queueable); a `Command` whose line is a give still counts as an item in the fit probe.
- `Droplist` spills on the ground, needs no room and has no `retryCommand`: a replay would roll differently.
- `RewardKindFold.foldInto` runs last, so an authored kind file overrides a Java kind with one warning per shadow; never move it earlier to hide the warning.
- A command-less kind file whose id a Java kind answers is presentation-only decoration; with no Java kind behind it, it is a `NO_COMMAND` error.
- A kind file's declared `Params` are the whole surface its `Command` line can substitute.
- One `RewardKindRegistry` carries a kind's handler and its `RewardAuthoring` adapter; never add a second table.
- A pass-scoped kind is a `CollectingRewardKind` registered once into `RewardKinds.shared()`, collecting through a facet on the Subject (`Subject.withFacets`), never a per-pass registry copy and never a new `LootEngine.Sinks` seam (a sink is a capability a pass wires; this is a kind content authors). Outside a pass it throws (counted lost, on the ledger), it reports nothing on the receipt and has no `retryCommand`. A site validator that no pass reaches (quest, achievement, shop, board) reports it through `CollectingRewardKind.siteWarning` (`PASS_ONLY_REWARD_KIND`); `LootableValidator` stays silent, since a table cannot know where it is rolled.
- `RewardChip` is the one chip record: a surface whose payout is not a `RewardSpec` still hands back a `RewardChip`.
- A kind's owner names Java-kind rewards through `RewardChips.contribute`; an authored `NameKey`, a shipped kind-file `Presentation` or an item form still wins.
- `RewardJson` refuses an under-specified reward at load, naming the file, never at payout.
