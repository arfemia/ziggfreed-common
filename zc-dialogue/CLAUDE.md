# CLAUDE.md - zc-dialogue

The branching NPC dialogue engine and the NPC spawn/placement engine. It sits high in the
dependency graph on purpose: a placement gate resolves world identity (`zc-world`), a dialogue
condition can read quest state (`zc-progression`), and the dialogue page paints through the shared
UI primitives (`zc-presentation`).

## Build

Part of the twelve-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-dialogue`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core`, `zc-presentation`, `zc-world`, `zc-progression`.
- **Depended on by**: no other library module today. This is a leaf on the consumer side of the
  graph; a mod that wants dialogue depends on it directly.
- **Reverse-edge trap**: `zc-progression` sits below this module and may never import anything from
  here. The quest-aware conversation vocabulary (`QuestState`/`ReadyToTurnIn`/`Accept`/`TurnIn`) is
  a one-way read through progression's narrow `QuestStateReader` seam and nothing else - never
  `QuestEngine`, which mutates: a dialogue condition that could reach the mutating engine could
  accept a quest while merely rendering a line.
- **A public signature re-exports a `zc-world` type**: `DialogueCondition.World#getSelector`
  returns a `world.WorldSelector`. The edge stays `implementation` (not `api`) because nothing
  depends on this module yet; the day a module depends on `zc-dialogue` AND calls `getSelector`, its
  own `zc-world` edge must be declared explicitly, or this edge becomes `api`.

## Packages

- [`dialogue/`](src/main/java/com/ziggfreed/common/dialogue/CLAUDE.md) - the generic branching NPC
  dialogue engine: a per-consumer `DialogueEngine` builder for behaviour over a process-wide
  `DialogueTypeTable` for the schema. Pre-seeds `Goto`/`Close`/`Remember`/`Forget`/`MarkTalked`/
  `OpenPage` actions and the `Remembered`/`NotRemembered`/`World`/`Factor` +
  `AllOf`/`AnyOf`/`Not` conditions. Subpackages `dialogue/asset/` (native `Parent` inheritance,
  `Server/ZiggfreedCommon/Dialogues/`), `dialogue/i18n/`, `dialogue/page/` (`DialoguePage`, the
  `.ui` this module ships), `dialogue/validate/` (the `Finding`-reporting content audit) have no
  routers of their own; the parent `dialogue/` router covers them.
  - [`dialogue/quest/`](src/main/java/com/ziggfreed/common/dialogue/quest/CLAUDE.md) - the
    quest-aware conversation vocabulary + the completion hand-off, reading through progression's
    `QuestStateReader` seam.
- [`npc/`](src/main/java/com/ziggfreed/common/npc/CLAUDE.md) - `ActionOpenDialogue` (open a
  `DialoguePage` on press-F via a registered NPC Action) + `NpcSpawnService`.
  - [`npc/placement/`](src/main/java/com/ziggfreed/common/npc/placement/CLAUDE.md) - the NPC
    placement engine: put an NPC somewhere, make press-F do something, keep exactly one of it
    standing. `NpcPlacementAsset`/`NpcPlacementConfig` at `Server/ZiggfreedCommon/NpcPlacements/`,
    resolving `Where` through `zc-world`'s selector vocabulary.

## Shipped resources

`Common/UI/Custom/Pages/{ZigDialoguePage.ui, ZigDialogueOptionRow.ui}` (the dialogue page + option
row, imports the shared frames from `zc-presentation`). `Server/ZiggfreedCommon/DialogueOptionTheme/
{Accept,Continue,Farewell,Neutral,Turnin}.json` (the five presentation themes an authored option's
`Presentation` block can reference by name).

## Conventions

`Once` is the keyless seen-ness knob on a `Start` entry or an option; named state is a declared
top-level `Memories` map used by bare name via `Remember`/`Forget` and `Remembered`/`NotRemembered`.
Option shorthand (`Open`/`Goto`/`Talk`/`Accept`/`TurnIn`/`Reward`/`Close`/`Do`) is a structured
schema leaf folded post-decode, not a pre-parse rewrite. A base conversation is an ordinary file
marked `Abstract` used as a native `Parent`, never a second resolver. `NpcPlacementAsset`'s `Where`
is the shared `{Names, Match, GameplayConfig, ExcludeNames}` group from `zc-world`, the one spelling
of "which worlds?" every selector-aware type in the library uses.

## Tests

32 files, the largest test suite in the library: the engine core (`DialogueEngineTest`,
`DialogueAuthoredFixtureTest`, `DialogueAuthoringAuditTest`), the state/scope model
(`DialogueOnceTest`, `DialogueMemoriesTest`, `DialogueFlagScopeTest`, `DialogueWorldConditionTest`,
`DialogueFactorConditionTest`, `DialogueStateValidationTest`), the quest vocabulary
(`DialogueQuestVocabularyTest`, `QuestCompletionDialogueValidatorTest`,
`QuestCompletionRoutingTest`), and the placement engine (`NpcPlacementAssetCodecTest`,
`NpcPlacementReconcilerTest`, `NpcPlacementValidatorTest`, `PlacementAnchorsTest`,
`PlacementChanceFormulaTest`, `PlacementGateChainTest`, `PlacementKeepAlivePinsTest`,
`PlacementRegistryTest`, `PlacementRegistryLedgerTest`, `RoleTemplatesTest`,
`NpcRoleGeneratorAppearanceTest`).
