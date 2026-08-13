# CLAUDE.md - `achievement/asset/` (module `zc-progression`)

The AUTHORING layer over [`../`](../CLAUDE.md)'s engine: one JSON file per achievement at
`Server/ZiggfreedCommon/Achievements/<Namespace>/<Feature>/<id>.json`, decoded straight into typed
fields (Pattern A) with native `Parent` inheritance.

| Class | What it is |
|---|---|
| `AchievementAsset` (+ nested `Listing`, `Scoring`) | the schema. The codec IS the schema; there is no hand parser beside it |
| `AchievementDefinition` | the folded result: the engine's `Achievement` plus the presentation and gate data the engine has no opinion about |
| `AchievementPool` | every folded achievement, ready for `engine.setAchievements(pool.achievements())` |
| `AchievementAssetStore` | the process-wide loaded layer; `resolveAll()` folds ALL of it |
| `AchievementPoolValidator` | the load-time audit, on the shared `Finding` core, `DOMAIN = "achievement"` |
| `AchievementCategoryAsset` + `AchievementCategoryConfig` | the TAXONOMY: how one grouping label is presented (`Order`/`Icon`/`TitleKey`/`Subcategories`), at `Server/ZiggfreedCommon/AchievementCategories/<category>.json` |
| `AchievementMilestoneAsset` + `AchievementMilestoneConfig` | the points LADDER: a reward for a running total, at `Server/ZiggfreedCommon/AchievementMilestones/<name>.json` |

## The taxonomy pair

Both are ordinary framework types: registered by the wiring root's `FrameworkAssetRegistrar`, folded
`defaults < pack < owner` through `AbstractKeyedAssetConfig`, and read LAZILY (the layer is filled by
the store's load event, long after any `setup()`).

- **A category asset is presentation and nothing else.** A category exists because content filed
  itself under that word in the shared `Listing.Category` leaf; these files only say where the word
  sits, what illustrates it, what it is called, and how its subcategories read. Every leaf is
  nullable, so a file changing one thing says only that thing.
- **A milestone's identity is its `Threshold`, not its filename.** `AchievementMilestoneConfig`
  collapses two files naming one number into one rung and hands them back ascending, which is what
  `AchievementEngine.setMilestones` wants. A file naming no threshold reaches nothing and is dropped
  rather than paying out on a player's first point.
- **The ids key plainly off the FILE name** for both, so a namespace folder
  (`AchievementMilestones/YourMod/...`) is organisational. `NestedAssetId` is deliberately NOT wired
  here: a category id has to equal the word content writes, and a milestone is addressed by its
  number.
- **Nothing in the milestone schema knows about queueing, retries, or a full backpack.** A consumer
  that has a policy about an undeliverable payout applies it as it folds; the file just says what is
  paid.

## Shared with the quest asset layer, deliberately

The overlapping groups are declared ONCE in [`../../progress/asset/`](../../progress/asset/CLAUDE.md)
and [`../../progress/gate/`](../../progress/gate/) so their field names cannot drift apart:

- `ContentTextAsset` - the `Text` group (`TitleKey` / `FlavorKey` / `DisplayName`)
- `ObjectiveLeafAsset` - the seven objective leaves each `Criteria` entry carries
- `RewardEntryAsset` - a `Rewards[]` / `ClaimRewards[]` entry (`Kind` + open `Params`)
- `GateSpec` / `GateClause` - the whole `Requires` block

Adding a field to any of them means adding it to BOTH engines at once, which is the point. A field
only one engine can have (a quest's `Order`, a hand-in place) belongs to that engine's own codec.

## Rules to keep

- **`Criteria` is an ORDERED ARRAY, and it is ONE leaf.** A child that authors `Criteria` REPLACES
  the parent's list whole; there is no per-index merge and there deliberately never will be, because
  a merge keyed by position would let a parent edit silently re-point every child's stored progress.
  Every other group merges leaf by leaf, the way `Parent` inheritance normally does.
- **A criterion's engine id IS its position** as a decimal string, assigned at fold time, which is
  also its progress key. What a reader sees and what a store writes therefore cannot disagree.
- **`Abstract` never carries down.** It is the one field excluded from inheritance: a child of a
  skeleton is a real achievement.
- **Basenames must be unique across the whole store**, even in different folders. The engine keys
  assets by FILENAME-minus-extension before our fold sees them, so two files sharing a basename
  collapse into one entry. The `_`-marked folder prefix (`asset/NestedAssetId`) namespaces IDS from
  differently named files; it does not make same-basename files coexist.
- **An unknown kind is a WARNING, never an error** (the mod that owns it may register later, or may
  not be installed); a REGISTERED kind that says it produces nothing is an ERROR.
- **New findings carry `DOMAIN`** and go through `Finding` / `ValidationReport`, never a bespoke
  report type.
