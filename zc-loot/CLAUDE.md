# zc-loot

The loot and reward layer: the one loot core (`loot/`), the shared reward vocabulary (`loot/reward/`), stat stamping (`loot/stamp/`) and the deferred-payout layer (`instance/reward/`).

- Depends on `zc-core` only, and six library modules depend on it, so any edge upward is a cycle. A grant that needs another module's capability is a reward kind registered from above: the wiring root registers `Effect` (zc-effects is out of reach here) beside `Droplist`.
- What loot IS lives in `loot/` only; anything in `instance/reward/` that reads like a second loot model is a mistake.
- `loot/stamp/StampFactors` contributes `ziggfreedcommon:item_stamp_points` process-wide from the wiring root, because zc-entity, which owns the `hytale:` item readings, has no edge to this module. A test that builds a real `ItemStack` is tagged `engine-items` and swaps the item store through zc-core's test fixture `testing/EngineAssetStores` (`testImplementation(testFixtures(project(':zc-core')))`), never a copy.
- A consumer's own reward token (such as `xp`) is a `RewardAuthoring` adapter on `RewardKinds.shared()`; an unregistered token drops its entry, never grants a phantom reward.
