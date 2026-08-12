# CLAUDE.md - `quest/asset/` (module `zc-progression`)

The AUTHORING layer over the quest engine: how a quest is written as a file, how a family of quests is written as one file, what a requirement means, and what a content audit reports. The engine core beside it stays hand-buildable and knows none of this.

Store paths (registered ONCE by the root `asset/FrameworkAssetRegistrar`, common OWNS them):
- `Server/ZiggfreedCommon/Quests/<id>.json` -> `QuestAsset`
- `Server/ZiggfreedCommon/QuestGenerators/<id>.json` -> `QuestGeneratorAsset` (loads AFTER Quests)

## The load path, end to end

```
files -> asset store (resolves Parent natively)   -> QuestAssetStore.mergeQuests
generators                                        -> QuestAssetStore.mergeGenerators
                     |
consumer: store.resolveAll(owner, enumerators)    -> expand generators -> decode each generated
                                                     body against its Base through THE SAME codec
                     |
                  QuestPool  -> engine.setQuests(pool.quests())
                             -> AssetQuestGates.pool(pool)   (the Requires block becomes the gate)
                             -> QuestPoolValidator.validate(pool, engine, gateKinds)
```

## The pieces

| Class | What it is |
|---|---|
| `QuestAsset` (+ `Listing`/`Flow`/`Repeat`/`Visibility`/`Npc`) | one authored quest; Pattern A, the codec IS the schema. Its `Text`, `Rewards`, and `Requires` groups are the SHARED ones below |
| `QuestObjectiveAsset` | a quest's objective: the shared `progress.asset.ObjectiveLeafAsset` leaves plus the two only a quest has (`Order`, `TurnInNpcId`) |
| [`progress.gate`](../../progress/gate/CLAUDE.md) `GateClause`, `GateSpec` | the `Requires` block: four shared leaves plus `AllOf`/`AnyOf`. SHARED with achievements |
| `progress.gate` `GateKind`, `GateKindRegistry` | the OPEN requirement vocabulary a consumer extends |
| `progress.gate` `GateEvaluator`, `AssetQuestGates` | what answers a `Requires` block, and the quest-engine bridge |
| `QuestDefinition`, `QuestPool` | the folded quest (engine model + presentation + gates) and the set of them |
| `QuestGeneratorAsset` (+ `Axis`), `QuestGeneratorExpander`, `GeneratedQuestBody` | one file writes a family |
| `QuestAxisRow`, `QuestValueEnumerator`, `QuestEnumeratorRegistry` | the OPEN value-source vocabulary an axis may name |
| `QuestAssetStore` | the loaded content, and the fold into a pool |
| `QuestPoolValidator` | the content audit; reports shared `validation.Finding` values under domain `quest` |
| [`progress.asset`](../../progress/asset/CLAUDE.md) `ContentTextAsset`, `ObjectiveLeafAsset`, `RewardEntryAsset`, `ProgressEditorDataSets` | the groups SHARED with the achievement asset layer, declared once so their field names cannot drift |
| `codec.JsonTreeCodec` (zc-core) | verbatim capture of an authored JSON subtree (the generator's `Child`, an axis's `Values`) |

## Rules to keep

- **Inheritance is NATIVE, and there is no template DSL.** A quest declares `"Parent": "<id>"` and the engine's own asset loading merges it. Every leaf is `appendInherited` and `Objectives` is an `InheritMapCodec`, which is the whole reason a child can retune one step and keep its siblings. Adding a field without `appendInherited` silently breaks that for the field; `QuestAssetCodecTest` guards the behaviour, not the spelling.
- **`Abstract` is the ONE field that must never inherit.** A child of a skeleton is a real quest. It was a plain `append` from the first day it existed for exactly this reason, and inheriting it makes every child of every base vanish from the pool.
- **The generator merges NOTHING.** It writes ordinary child bodies carrying `Parent` and lets the same decode do the rest. `QuestGeneratorTest.ByteEquivalence` is a release gate on that: it authors a quest by hand AND generates it, then compares both the emitted JSON and the folded result. If a change makes that test hard to keep, the change is wrong.
- **Substitution rules, in full**: every string value, every object KEY, and `IdPattern`; a value that is EXACTLY one token keeps that token's own type (so `"Amount": "{amount}"` lands as a number); a token nothing binds is an ERROR finding and that one quest is skipped rather than shipped half-written.
- **Gates fail closed, everywhere.** No factor registry, no permission probe, no completion probe, an unregistered `Custom` kind, a kind that throws: all refuse. A quest that authors no requirements needs no wiring at all, which is what keeps the fail-closed default from being a burden.
- **Display text is keys.** `Text.TitleKey`/`FlavorKey` and an objective's `TextKey` are localization keys the player's own client resolves. `Text.DisplayName` exists only as a fallback while a key is being written; never route shipped content through it.
- **`QuestDefinition` carries what the engine deliberately does not model** (text keys, category, sort order, the NPC ids, the gate block). Do not push presentation into `Quest`; hand the engine `pool.quests()` and read the rest here.
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
- **A `$Comment` in any of these files is a TIP for the server owner or pack author.** Both the codec and `InheritMapCodec` skip `$`-prefixed keys, so an authored map can be documented inline.

## Adding to it

- A new quest field: a leaf in the group it belongs to, `appendInherited`, with documentation written for an author (what it does in game, what unauthored means). A cohesive pair or trio is a new nested group, never a flat prefixed key.
- A new requirement: prefer a registered `GateKind` over a new leaf. Only the genuinely universal requirements are leaves.
- A new finding: add it to `QuestPoolValidator` with a stable code, and pick the severity by the rule at the top of that class - unknown means "some mod may supply it later" (warning), impossible means an error.
- Tests are mechanics, structure, and invariants. Fixture content is author-owned; never assert numbers that belong to somebody's balance pass.
