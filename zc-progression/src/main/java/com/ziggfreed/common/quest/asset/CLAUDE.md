# quest/asset/

- `ProgressionDefaults.publishAssetContent` (zc-objectives) is the one quest publish: loaded < contributed (`QuestAssetStore.mergeContributed`, one untracked layer) < the owner folder `mods/ziggfreedcommon/quests/<Id>.json`, each winning per id. A consumer publishes no quest layer of its own.
- The fold reads the owner folder on every publish (`QuestOwnerLayers`); never cache it or add a listener for it.
- Asset basenames must be unique across the store: a `_`-marked folder folds its name into the id but cannot make two same-named files coexist. Renaming a marked folder renames every id under it, which resets saved progress.
- `TurnInAt` (where the finished quest is collected) and an objective's `TurnInNpcId` (a delivery step) are different leaves. Its sentinels (`true` or `giver`, `@accept`, empty or `false`) resolve at the fold through `QuestAsset.resolveTurnInAt`; another quest format decodes the leaf through `QuestAsset.BooleanOrStringCodec` and hands the word to that method.
- Visibility is three orthogonal knobs: `Listing.Hidden`, `Listing.RequirePrerequisites`, and the quest-only `Listing.ShowWhen`, which is never consulted on accept.
- A kind alias applies once, at the fold (`ObjectiveLeafAsset.toDefBuilder`); register aliases at setup, before the publish.
- `Indicator` is one block at three scopes (the global `Server/ZiggfreedCommon/QuestIndicators/Default.json`, a quest, a step), decoded only through `QuestIndicatorSpec`.
- Presentation data stays on `QuestDefinition`, never on `Quest`; the collection site lives on `Quest` because the engine enforces it.
- `QuestGeneratorTest.ByteEquivalence` (a hand-written and a generated quest must match) gates any generator change.
- `Season` (zc-core `SeasonLeaf`) hides a quest outside its calendar event and survives `Parent` whatever a child writes in `Requires`; `resolve` reports an id no event declares (`UNKNOWN_SEASON`) once per authored file, a skeleton included, never per generated child.
- A generated family follows its base through the mod gate: a generator whose `Base` the load handler refused (`mergeQuests(layer, refused)`) writes nothing and reports nothing, a base nobody authored still reports `UNKNOWN_BASE`, and a generated child whose body names an absent mod is dropped like a file. A generator has no `Requires`; gate its base.
