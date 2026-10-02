# instance/reward/

- `DeferredRewards.fromSelection` is the one translation from a `LootEngine.select` decision into rewards shown now and paid later. A registered kind with no replayable line is dropped and reported, never promised.
- Hand `DeferredRewards` a subject named `{player}`, so a deferred command resolves whoever claims it.
- `InstanceRewardGranter` is block-first: an `ITEM` is granted only if all of it fits, otherwise it is held pending, never partly delivered.
- Register a consumer's reward-token adapter at `setup()`, before `LoadedAssetsEvent`: preset reward specs parse during the asset fold.
- `NativeLootService`: inside a tick use the `(store, commandBuffer)` spawn form. The one-accessor form re-queues an add the engine rejects mid-tick onto the world thread, and every spawn form answers landed-or-queued versus lost.
- Every native drop-list roll goes through `rollNative` (empty when nothing could roll), or `tryRollNative` for a probe that must tell an empty roll from one never made (null); never `ItemModule.getRandomItemDrops` directly.
- `NativeLootServiceTest` is untagged: in the default `test` task no `ItemStack` can be built (its codec chain needs the engine's log manager), so it proves only the guards.
- A unit JVM never boots `ItemModule`, so `NativeLootService` tests prove its guards against the unbooted engine; what a live drop list produces is checked in game.
- `LootEntry`'s compact grammar has no live caller: author a `Lootable` `Pool` unless a codec field can only hold a `String[]`.
