# CLAUDE.md - `quest/asset/` (module `zc-progression`)

The AUTHORING layer over the quest engine: how a quest is written as a file, how a family of quests is written as one file, what a requirement means, and what a content audit reports. The engine core beside it stays hand-buildable and knows none of this.

Store paths (registered ONCE by the root `asset/FrameworkAssetRegistrar`, common OWNS them):
- `Server/ZiggfreedCommon/Quests/<id>.json` -> `QuestAsset`
- `Server/ZiggfreedCommon/QuestGenerators/<id>.json` -> `QuestGeneratorAsset` (loads AFTER Quests)

## The load path, end to end

```
files -> asset store (resolves Parent natively)   -> QuestAssetStore.mergeQuests      (the LOADED layer)
generators                                        -> QuestAssetStore.mergeGenerators
a consumer's own decoded bodies                   -> QuestAssetStore.mergeContributed(layer)  (ONE layer)
mods/ziggfreedcommon/quests/<Id>.json             -> read by the fold itself (QuestOwnerLayers), never merged
                     |
the shared publish: store.resolve(ProgressionRuntime.questAxes())
                                                  -> compose: loaded < contributed < the owner folder,
                                                     each winning per id; an owner file decoded
                                                     against the quest below it (or its Parent)
                                                     through THE SAME codec
                                                  -> expand generators against that composed view ->
                                                     decode each generated body against its Base
                                                     through THE SAME codec
                                                  -> QuestAsset.toDefinition: lift the hide axis
                                                     (FeatureLift.liftKnown), apply any kind alias,
                                                     add the report-back step Npc.TurnInId implies,
                                                     stamp available() live
                     |
                  QuestPool  -> engine.setQuests(pool.quests())
                             -> the Requires block rides the runtime Quest, and RequiresGates reads it
                             -> QuestPoolValidator.validate(pool, engine, gateKinds)
```

The publish is `ProgressionDefaults.publishAssetContent` (zc-objectives), the ONE quest publish on a
server: it resolves with the runtime's own axis vocabulary, folds the contributed layer and the
owner folder, keeps the pool and the fold's findings readable (`questPool()` / `questLoadFindings()`)
for a consumer's audit, and runs again on a hot re-import (`republishAssetContent`, asked by the root
registrar's own `LoadedAssetsEvent` listeners once this boot has published) and on `/zigprogress
reload`. It runs FIRST the moment the shared runtime is BUILT, whoever built it (hung on
`ProgressionRuntime.onBuilt` by `ProgressionDefaults.register()`), so a ticking system's first engine
read and the first player-ready pass see the same catalogue. A consumer publishes no quest layer of
its own; it fills seams.

## The pieces

| Class | What it is |
|---|---|
| `QuestAsset` (+ `Listing`/`Flow`/`Repeat`(+`.Reset`)/`Npc`) | one authored quest; Pattern A, the codec IS the schema. Its `Text`, `Rewards`, and `Requires` groups are the SHARED ones below, and the three visibility knobs ride `Listing` (the shared `Hidden` and `RequirePrerequisites` booleans plus this type's own `ShowWhen`, a block in the same shape as `Requires` that hides the quest until it passes, independently of the accept gate, never consulted on accept, and ignored for a quest the player already holds), folding into the engine's `Quest.Visibility` at the fold. `toDefinition` is where the HIDE AXIS is folded: a plain top-level feature or mod-presence condition leaves `Requires` through `progress.gate.FeatureLift.liftKnown` (every namespace `factor.FeatureFlags` has declared plus `hytale:mod_installed`), lands on the record's `lifted()`, and `Quest.available()` answers `Enabled` AND every lifted condition LIVE on each read. Authoring `Repeat` AT ALL is what makes a quest repeatable, so `"Repeat": {}` is the smallest one; `Reset.Every` is the window length as a `DurationGroup` (`Weeks`/`Days`/`Hours`/`Minutes`/`Seconds`, any length) with `Period` Daily|Weekly as its sugar (both authored: `Every` wins and `REPEAT_EVERY_AND_PERIOD` says so); `TurnInAt` and `CompletionDialogue` are top-level leaves beside `Npc`. `toDefinition` also ADDS the report-back step a quest-level `Npc.TurnInId` implies when no authored step is a `TURN_IN`: id `turn_in` (`REPORT_BACK_ID`, what saved progress files it under), empty target, order one past the highest authored, locked to the resolved place, and the `objective.text.turn_in.find` key (`REPORT_BACK_FIND_KEY`) on the record when that place is not the giver; a `giver` sentinel with no `ViewId` adds nothing. Pinned by `QuestReportBackStepTest` |
| `QuestObjectiveAsset` | a quest's objective: the shared `progress.asset.ObjectiveLeafAsset` leaves plus the two only a quest has (`Order`, `TurnInNpcId`) |
| [`progress.gate`](../../progress/gate/CLAUDE.md) `GateClause`, `GateSpec` | the `Requires` block: four shared leaves plus `AllOf`/`AnyOf`. SHARED with achievements |
| `progress.gate` `GateKind`, `GateKindRegistry` | the OPEN requirement vocabulary a consumer extends |
| `progress.gate` `GateEvaluator`, `quest.RequiresGates` | what answers a `Requires` block, and the ONE gate both engines read it through |
| `QuestDefinition`, `QuestPool` | the folded quest (engine model + presentation + gates) and the set of them. The record carries the gate WITH the hide axis lifted out (`requires()` never holds a plain feature/mod-presence condition; `lifted()` is what left it, `features()` the flat hide-axis list a consumer's own predicate reads) and `lore()`, the plain per-state fallback paragraphs `Text.Lore` authored, stamped onto `ContentText.lore(state)` |
| `QuestGeneratorAsset` (+ `Axis`), `QuestGeneratorExpander`, `GeneratedQuestBody` | one file writes a family |
| `QuestAxisRow`, `QuestValueEnumerator`, `QuestEnumeratorRegistry` | the OPEN value-source vocabulary an axis may name |
| `QuestAssetStore` | the loaded content (the LOADED layer, `mergeQuests` / `mergeGenerators`, rebuilt wholesale per load event), the ONE contributed layer (`mergeContributed(Map<String, QuestAsset>)` hands in a whole layer of already-decoded bodies - an older format a consumer converts at its own load event - replaced whole by the next hand-in and taken away by an empty one; untracked, nothing about who handed it in is kept), and the fold into a pool (`resolve(enumerators)`): loaded < contributed < the owner folder, each layer winning per id SILENTLY (an owner's file over a converted one over a pack's is what a layer is for), while an id twice inside one layer is `DUPLICATE_QUEST_ID` naming the layer. Generators expand against the composed view (`composedAssets()`, which reads the folder afresh too); `assets()` stays the loaded layer alone |
| `QuestOwnerLayers` | the server owner's quest folder, `mods/ziggfreedcommon/quests/<Id>.json`: one shared-shape `QuestAsset` per file, the file name the id (lower-cased), read by EVERY fold and laid over both other layers with no registration and no listener of its own. A same-id file merges leaf by leaf over the quest below it (that quest is its implicit `Parent`, decoded through the same codec and per-leaf inheritance a pack child uses); an explicit `Parent` inherits from that quest wherever it lives (a pack file, the contributed layer, another owner file, resolved recursively so name order never matters); a new id stands alone. Findings under the `quest` domain naming the file: `OWNER_FILE_UNREADABLE`, `DECODE_FAILED`, `UNKNOWN_PARENT`, `PARENT_CYCLE`, `DUPLICATE_QUEST_ID`, `OWNER_FOLDER_UNREADABLE`; a malformed file costs that one quest, never the boot. `$`-prefixed names, non-`.json` entries and sub-folders are ignored (flat folder, no marked sub-folders). `setDirectory` relocates the parent directory for a test or a consumer data dir |
| `QuestPoolValidator` (+ `.NpcIdProbe`) | the content audit; reports shared `validation.Finding` values under domain `quest`. The probe is the optional seam for "does anything answer to this character id", since who stands where is declared above this module |
| [`progress.asset`](../../progress/asset/CLAUDE.md) `ObjectiveLeafAsset`, `RewardEntryAsset`, `ProgressEditorDataSets`, plus zc-core's `ContentTextAsset` (`com.ziggfreed.common.text`) | the groups SHARED with the achievement asset layer, declared once so their field names cannot drift |
| `codec.JsonTreeCodec` (zc-core) | verbatim capture of an authored JSON subtree (the generator's `Child`, an axis's `Values`) |

## Rules to keep

- **Inheritance is NATIVE, and there is no template DSL.** A quest declares `"Parent": "<id>"` and the engine's own asset loading merges it. Every leaf is `appendInherited` and `Objectives` is an `InheritMapCodec`, which is the whole reason a child can retune one step and keep its siblings. Adding a field without `appendInherited` silently breaks that for the field; `QuestAssetCodecTest` guards the behaviour, not the spelling.
- **`Abstract` is the ONE field that must never inherit.** A child of a skeleton is a real quest. It was a plain `append` from the first day it existed for exactly this reason, and inheriting it makes every child of every base vanish from the pool.
- **The generator merges NOTHING.** It writes ordinary child bodies carrying `Parent` and lets the same decode do the rest. `QuestGeneratorTest.ByteEquivalence` is a release gate on that: it authors a quest by hand AND generates it, then compares both the emitted JSON and the folded result. If a change makes that test hard to keep, the change is wrong.
- **Substitution rules, in full**: every string value, every object KEY, and `IdPattern`; a value that is EXACTLY one token keeps that token's own type (so `"Amount": "{amount}"` lands as a number); a token nothing binds is an ERROR finding and that one quest is skipped rather than shipped half-written.
- **Gates fail closed, everywhere.** No factor registry, no permission probe, no completion probe, an unregistered `Custom` kind, a kind that throws: all refuse. A quest that authors no requirements needs no wiring at all, which is what keeps the fail-closed default from being a burden.
- **`TurnInAt` and an objective's `TurnInNpcId` answer different questions, and both stay.** The
  quest-level leaf says where the finished quest may be COLLECTED and is enforced by the engine's
  `canCompleteAt` in the completion path itself; an objective's is a delivery STEP locked to a
  character, enforced when that step is handed in. A quest may have one, the other, both, or neither.
  The leaf is a dual-form scalar so the commonest answer is one word: `true` (or `giver`) is whoever
  offers it, a bare id is that character, `@accept` is wherever this player took it from, and an
  empty string or `false` clears one inherited from a `Parent`. Both sentinels are resolved AT THE
  FOLD, so the engine only ever sees a resolved site; a `giver` form on a quest with no `Npc.ViewId`
  folds to a site nobody can be rather than to "anywhere", which is what makes the audit finding
  possible instead of a quest that silently behaves differently from what it says. The reading is
  ONE public static, `QuestAsset.resolveTurnInAt(authored, giverId)` (the instance `turnInSite`
  delegates to it), and the dual-form leaf codec is the public `QuestAsset.BooleanOrStringCodec`:
  a consumer whose own quest format carries the same leaf decodes it through that codec and hands
  the word to that static, so the four spellings can never drift between formats. Pinned by
  `QuestTurnInAtCodecTest`, the parser's own cases included.
- **Display text is keys.** `Text.TitleKey`/`FlavorKey` and an objective's `TextKey` are localization keys the player's own client resolves. `Text.DisplayName` exists only as a fallback while a key is being written; never route shipped content through it. `Text.Lore` (`{Incomplete, Active, Complete}`, zc-core's `ContentTextAsset.Lore`) is on the same contract: a raw paragraph per lifecycle state that the `quest.<id>.md.<state>` convention key outranks whenever it ships, so author the key for anything shipped.
- **Visibility is three orthogonal knobs, never a mode.** `Listing.Hidden` (off open listings, still offered at its giver), `Listing.RequirePrerequisites` (hidden until the ACCEPT gate passes) and `Listing.ShowWhen` (hidden until its OWN block passes, read through the same `GateEvaluator` over the same factor context as `Requires`, so a consumer's factor answers it; the accept gate stays `Requires` alone). `QuestEngine.isVisible` is THE shared visibility question a browsable surface asks and a consumer's own listing rule collapses onto; `isOfferable` applies `ShowWhen` too (it says "not yet", where `Hidden` says "not here"). `QuestPoolValidator` audits the block like `Requires`, each finding prefixed `SHOW_WHEN_` and saying which block. Quests only: the achievement engine has no such leaf.
- **A kind alias is applied at the fold, once.** `ObjectiveLeafAsset.toDefBuilder` reads `ProgressionRuntime.objectiveKinds().alias(kind)`: an authored kind registered as sugar for another builds the engine's pair (`kind()`/`target()` the alias's run kind and rewritten target) and keeps the file's pair (`authoredKind()`/`authoredTarget()`) for every text and icon surface. Register aliases at setup, before the publish.
- **`QuestDefinition` carries what the engine deliberately does not model** (text keys, category, sort order, the NPC ids, the gate block). Do not push presentation into `Quest`; hand the engine `pool.quests()` and read the rest here. The collection site is the exception that proves it: the engine ENFORCES that one, so it lives on `Quest` and this record only reads it back. `withTurnInAt` is the one-line stamp for a consumer whose content declares a site by POLICY rather than per file, so no author writes the leaf on every one of those quests.
- **A quest's id can carry its folder.** The engine keys an asset by its FILENAME alone, so two
  files of the same name in different folders are one id and the second silently replaces the first.
  A folder marked with a leading underscore contributes its name to the id instead:
  `Quests/Zones/_Wilds/Trork_Trouble.json` is `wilds_trork_trouble`, while an UNMARKED folder
  contributes nothing (so every existing tree keeps the ids it has). `QuestAsset`'s `afterDecode`
  does the fold via `asset/NestedAssetId` off `AssetExtraInfo.getAssetPath()`, and `QuestAssetStore`
  files each quest under that EFFECTIVE id rather than the event key. Two files landing on one id is
  a `DUPLICATE_QUEST_ID` ERROR naming both paths, and the reserved-delimiter check runs on the
  prefixed id (a marked folder can carry a bad character too). A GENERATED quest has no file behind
  it, so it takes no prefix and keeps its generated id. Renaming a marked folder renames every id
  beneath it, and an id is what a player's saved progress is filed under.
- **`Indicator` is ONE block written at three scopes, and `QuestIndicatorSpec` is its only schema.** A quest's `Indicator` (a leaf on `QuestAsset`) and a step's `Indicator` (a leaf on `QuestObjectiveAsset`) decode through the same codec the global `Server/ZiggfreedCommon/QuestIndicators/Default.json` (zc-objectives) appends onto its asset codec via `QuestIndicatorSpec.appendLeaves`, so the four groups (`Collect`, `TurnIn`, `Available`, `InProgress`, the `QuestSituation` vocabulary in precedence order) and their `{Enabled, State, Overhead: {Enabled}, Map: {Enabled, Icon}}` leaves cannot drift between scopes. Every leaf is nullable and `appendInherited`, so a child under `Parent` retunes one leaf and keeps the rest; across scopes the overlay is `QuestIndicatorSpec.merge(base, over)` and the reading `resolve(situation)`, defaults last (overhead on under the situation's own state, map off). Both blocks ride the runtime `Quest` (`indicator()`, `stepIndicator(objectiveId)`, carried by every copy), since the engine that decides what a character shows sits above this module and reads the runtime object; a step's block is read only for the situation THAT step raises (a hand-in step's `TurnIn`, a carried step's `InProgress`). `QuestIndicatorSpecTest` and `QuestIndicatorLeafTest` pin the decode, the defaults, the per-leaf merge and the inheritance.
- **The owner folder is the third layer, not a registration.** `mods/ziggfreedcommon/quests/<Id>.json`
  is read by the fold itself (`QuestOwnerLayers.read`, from `QuestAssetStore.compose`), so whatever
  re-publishes the store re-reads it: the boot publish, `/zigprogress reload`, every hot re-import
  listener. Nothing merges it, nothing listens for it, and nothing about it is stored between folds.
  Keep it that way: a cached owner layer would be decoded against a layer below that has since
  changed, and a listener would be a second path to keep in step with the fold. The MERGE rule is the
  codec's own inheritance (`decodeAndInheritJsonAsset` against the quest below, or the named
  `Parent`), never a hand-rolled overlay, which is why `appendInherited` on every leaf matters here
  exactly as it does for a pack child.
- **A `$Comment` in any of these files is a TIP for the server owner or pack author.** Both the codec and `InheritMapCodec` skip `$`-prefixed keys, so an authored map can be documented inline.

## Adding to it

- A new quest field: a leaf in the group it belongs to, `appendInherited`, with documentation written for an author (what it does in game, what unauthored means). A cohesive pair or trio is a new nested group, never a flat prefixed key.
- A new requirement: prefer a registered `GateKind` over a new leaf. Only the genuinely universal requirements are leaves.
- A new finding: add it to `QuestPoolValidator` with a stable code, and pick the severity by the rule at the top of that class - unknown means "some mod may supply it later" (warning), impossible means an error.
- Tests are mechanics, structure, and invariants. Fixture content is author-owned; never assert numbers that belong to somebody's balance pass.
