# objectives/store/ - the persisted progress component

- One persisted component (`ZigProgressComponent`), two adapters (`ZigQuestStore`, `ZigAchievementStore`): the two store interfaces' `status` methods share an erasure, so no single class can implement both.
- `ProgressBlob` packs each map as `key=value|key=value`, opaque values base64-encoded. Never insert or rename a leaf, and treat a changed spelling as a data migration; the golden fixture `src/test/resources/fixtures/zig-progress-blob-1-6-0.bin` is never regenerated.
- A read never creates the component: no component means neutral reads and dropped writes. The one create path is `PlayerConnectEvent`.
- `markDirty` and `flush` write nothing themselves; they fan out to the consumer listeners registered through `ProgressionDefaults.onProgressDirty` and `onProgressFlush`. Flush fires only at the five collect or payout points (a quest, achievement or milestone collected; an auto-claim or an admin close-out that delivered something). Nothing in a self-heal, re-arm, prune or pin sweep commits.
- A write that goes around the engine (the component or a store adapter called directly) reports nothing, so whoever makes it marks the player dirty itself.
- A consumer never brings a second store: it supplies a `ProgressionSubjectSource` whose handle offers the component, and these adapters stay the store.
