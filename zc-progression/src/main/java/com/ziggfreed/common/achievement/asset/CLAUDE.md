# CLAUDE.md - `achievement/asset/` (module `zc-progression`)

The AUTHORING layer over [`../`](../CLAUDE.md)'s engine: one JSON file per achievement at
`Server/ZiggfreedCommon/Achievements/<Namespace>/<Feature>/<id>.json`, decoded straight into typed
fields (Pattern A) with native `Parent` inheritance.

| Class | What it is |
|---|---|
| `AchievementAsset` (+ nested `Listing`, `Scoring`) | the schema. The codec IS the schema; there is no hand parser beside it |
| `AchievementDefinition` | the folded result: the engine's `Achievement` plus the presentation and gate data the engine has no opinion about; its constructor STAMPS the requires / text / icon AND the listing (category / subcategory / sort order / chains) onto the runtime object, so a shared surface reads them with no definition lookup |
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
and [`../../progress/gate/`](../../progress/gate/CLAUDE.md) so their field names cannot drift apart:

- `ContentTextAsset` (zc-core's `com.ziggfreed.common.text`) - the `Text` group (`TitleKey` / `FlavorKey` / `DisplayName` / `TextArgs`)
- `ObjectiveLeafAsset` - the seven objective leaves each `Criteria` entry carries
- `RewardEntryAsset` - one entry of a `Rewards` bucket (`Kind` + open `Params`)
- `ContentRewardsAsset` - the `Rewards` group (`Auto` pays on settling, `Claim` waits to be collected)
- `GateSpec` / `GateClause` - the whole `Requires` block

Adding a field to any of them means adding it to BOTH engines at once, which is the point. A field
only one engine can have (a quest's `Order`, a hand-in place) belongs to that engine's own codec.

## Rules to keep

- **`Criteria` is a KEYED MAP, and it merges per criterion id.** A child that carries `Parent` may
  retune one criterion by its key and keeps every criterion it did not mention, the same per-key
  merge (`InheritMapCodec`) every other group gets. A parent edit can never silently re-point a
  child's stored progress, because progress follows the KEY, not a position.
- **A criterion's engine id IS its authored KEY**, taken at fold time, and it is also its progress
  key. So renaming a key starts that criterion over for everybody, while adding, removing or
  reordering entries never moves anyone's progress.
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
