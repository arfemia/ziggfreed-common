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
- **Depended on by**: `zc-objectives`, and only for NPC IDENTITY - its NPC quest page names a
  character through `npc/NpcNames` and folds the character's alias set through `npc/NpcIdentities`,
  rather than making every consumer say who is standing there. That module is the top of the graph,
  so the edge closes nothing. Nothing else in the library depends on this one; a mod that wants
  dialogue depends on it directly.
- **Reverse-edge trap**: `zc-progression` sits below this module and may never import anything from
  here. The quest-aware conversation vocabulary (`QuestState`/`ReadyToTurnIn`/`Accept`/`TurnIn`) is
  a one-way read through progression's narrow `QuestStateReader` seam and nothing else - never
  `QuestEngine`, which mutates: a dialogue condition that could reach the mutating engine could
  accept a quest while merely rendering a line.
- **A public signature re-exports a `zc-world` type**: `DialogueCondition.World#getSelector`
  returns a `world.WorldSelector`. The edge stays `implementation` (not `api`) because the one
  module that depends on this one reads NPC identity and never calls `getSelector`, so no
  `world.WorldSelector` has to resolve on its compile classpath; the day a dependent does call it,
  that dependent declares its own `zc-world` edge explicitly, or this edge becomes `api`.

## Packages

- [`dialogue/`](src/main/java/com/ziggfreed/common/dialogue/CLAUDE.md) - the generic branching NPC
  dialogue engine: ONE `DialogueEngine` per server that every mod registers into additively, over
  the process-wide `DialogueTypeTable` that reads the files. Seeds
  `Goto`/`Close`/`Remember`/`Forget`/`MarkTalked`/`OpenPage` actions and the
  `Remembered`/`NotRemembered`/`World`/`Factor` + `AllOf`/`AnyOf`/`Not` conditions once. The
  builder stays for an isolated test engine. The six front-door classes (`DialogueEngine`,
  `DialogueContext`, `DialogueExecContext`, `DialoguePayloads`, `DialogueQuestView`,
  `DialogueTalk`) sit at the package root; around them `dialogue/type/` (the registration
  contract), `dialogue/schema/` (the model + codec assembly) and `dialogue/state/` (seen-ness,
  memories, flag stores) each carry their own router, and `dialogue/style/` (the option look +
  its data-driven theme) is covered by the parent. Subpackages `dialogue/asset/` (native `Parent` inheritance,
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
    standing. `asset/NpcPlacementAsset`/`asset/NpcPlacementConfig` at
    `Server/ZiggfreedCommon/NpcPlacements/`, resolving `Where` through `zc-world`'s selector
    vocabulary. Split into `asset/` + `registry/` + `anchor/` + `runtime/` + `interact/` (each of
    the first four with its own router) beside the pre-existing `admin/` + `command/`.

## Shipped resources

`Common/UI/Custom/Pages/{ZigDialoguePage.ui, ZigDialogueOptionRow.ui}` (the dialogue page + option
row, imports the shared frames from `zc-presentation`). `Server/ZiggfreedCommon/DialogueOptionTheme/
{Accept,Continue,Farewell,Neutral,Turnin}.json` (the five presentation themes an authored option's
`Presentation` block can reference by name).

## Conventions

`Start` is declared SECTIONS (`First`/`Quests`/`Then`/`Fallback`) walked in an engine-owned ladder,
never a hand-sorted list. `Once` is the keyless seen-ness knob on a `Start` beat or an option; named
state is a declared top-level `Memories` map used by bare name via `Remember`/`Forget` and
`Remembered`/`NotRemembered`. Option shorthand (`Open`/`Goto`/`Accept`/`TurnIn`/`Reward`/`Close`/
`Do`) is a structured schema leaf folded post-decode, not a pre-parse rewrite, and `Open`'s value is
a `ui/route/Destination` rather than a routing string. A base conversation is an ordinary file marked
`Abstract` used as a native `Parent`, never a second resolver. **`Where` is the shared
`{Match, GameplayConfig, ExcludeMatch}` group from `zc-world` EVERYWHERE it appears** - an NPC
placement's, a `World` condition's, and the per-world scope on a `Once` or a `Memories` declaration -
the one spelling of "which worlds?" in the library.

## Tests

38 test files beside three shared fixtures, the largest suite in the library: the engine core
(`DialogueEngineTest`, `DialogueAuthoredFixtureTest`, `DialogueAuthoringAuditTest`), the opening
ladder (`DialogueStartTest`: the fixed rung order, the READY rule, the weighted draw against an
injected number, and the audit's Start findings), the state/scope model
(`DialogueOnceTest`, `DialogueMemoriesTest`, `DialogueFlagScopeTest`, `DialogueWorldConditionTest`,
`DialogueFactorConditionTest`, `DialogueStateValidationTest`), the quest vocabulary
(`DialogueQuestVocabularyTest`, `QuestCompletionDialogueValidatorTest`,
`QuestCompletionRoutingTest`), the one shared engine (`DialogueSharedEngineTest`: two mods' actions
mixing in one option, a late registration reaching an engine a caller already holds, a second
contributor for a class already claimed being refused and reported once, the singular quest slot
refusing a second runtime, and the builder's sandbox staying separate) plus its payload seam
(`page/SimpleDialogueExecContextPayloadTest`: explicit-first, then the registered supplier), and the
placement engine (`NpcPlacementAssetCodecTest`,
`NpcPlacementReconcilerTest`, `NpcPlacementValidatorTest`, `NpcPlacementAuditScopeTest`,
`NpcPlacementConfigAuditTest`,
`PlacementAnchorsTest`,
`PlacementChanceFormulaTest`, `PlacementGateChainTest`, `PlacementKeepAlivePinsTest`,
`PlacementRegistryTest`, `PlacementRegistryLedgerTest`, `RoleGenerationRetirementTest`).
