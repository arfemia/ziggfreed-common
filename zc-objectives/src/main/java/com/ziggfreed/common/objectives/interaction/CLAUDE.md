# objectives/interaction/ - item interaction Types that feed progression and pay rewards

- `ZigCreditProgress` and `ZigGrantReward` are side-effecting: `simulateFirstRun` is empty, and each resolves `Finished` on every path it decides, so a missing player or a lost roll never breaks the chain that carries it.
- A credit or a payout names the item the chain STARTED with (`UsedItems`), never only the hand: a `ModifyInventory` earlier in the chain may have spent the last of the stack.
- `ZigCreditProgress` fires through `ProgressDispatch.fire` with no payload and credits only an accumulating kind the vocabulary knows (`ProgressCredit.creditable`); `ZigGrantReward` pays through `RewardGrants` with the runtime's subject and retry queue, in the quest `Rewards` entry shape, never a second reward vocabulary.
- Never construct a Type, call its `getCODEC()` or call any static method on a Type class from a test: its codec chains into `Interaction`'s class init, which throws outside a live server. Test the pure cores (`ProgressCredit`, `InteractionRewards`, `UsedItems.pick`) and `ProgressInteractionsBootstrap.specs()`.
- The wiring root calls `ProgressInteractionsBootstrap.registerProgressInteractions` in `setup()`; without it every item naming these Types fails to load.
- `InteractionRewards` is the one payout core for an authored `Rewards` list outside a quest: `ZigGrantReward` here and the dialogue `Grant` (`objectives/dialogue/GrantDialogueAction`) both pay through it, so never a third copy of the roll, the label or the subject.
