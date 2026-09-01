# CLAUDE.md - zc-progression

The consumer-agnostic PROGRESSION engines and the shared cores under them. `progress/` holds what
every lifecycle engine here shares (the registered objective vocabulary, the ONE forgiving matching rule, the
progress + objective model, the dispatch knobs, and the shared runtime registration surface);
`quest/` and `achievement/` are the two lifecycle engines built on top. It carries no content and no
domain vocabulary of its own.

## Build

Part of the thirteen-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-progression`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core` (the shared registry + logging primitives), `zc-loot` (the reward
  VOCABULARY in `loot.reward` - what a reward is, who pays it out, and the isolated payout pass;
  the edge only ever points this way).
- **Depended on by**: `zc-dialogue` (the quest-aware conversation vocabulary reads through
  `QuestStateReader`), `zc-objectives` (the shared runtime this module's engines register into),
  `zc-commerce` (bounties ARE quests: the board engine reads and mutates through the quest engine,
  and a board's `Requires`/`AcceptRequires` blocks are this module's `GateSpec`).
- **Reverse-edge trap**: this module sits below `zc-dialogue`, so it may never import dialogue,
  presentation, or world. A turn-in conversation, a results page, or a waypoint is a SEAM the
  wiring root or the consumer fills, never an import - see the quest package router.

## Packages

- [`progress/`](src/main/java/com/ziggfreed/common/progress/CLAUDE.md) - the shared lifecycle
  cores: `ObjectiveDef`, `ObjectiveKind`/`ObjectiveKindRegistry`, `MatchMode`/
  `ObjectiveMatch`/`ZoneRef`/`ZoneLocator` (the ONE read of where a player is, off the engine's
  `WorldMapTracker`), `ObjectiveProgressState`, `ObjectiveIndex`, `DispatchOptions`, and `ObjectiveComposer`/`NeutralObjectiveComposer` (the sentence a key-less step reads with: a consumer-installable slot over the shipped nine-locale per-kind family in this module's `ziggfreedcommon.progress.lang`).
  - [`progress/asset/`](src/main/java/com/ziggfreed/common/progress/asset/CLAUDE.md) - the authoring
    groups both engines share (`ContentMeta`, `ObjectiveLeafAsset`, `RewardEntryAsset`,
    `ProgressEditorDataSets`), declared once so their field names cannot drift between quest and
    achievement files, plus `ContentTextAsset` (`Text`), which both engines' codecs decode through
    but which lives in zc-core's `com.ziggfreed.common.text` since a module below this one needs it
    too.
  - `progress/docs/` - `SchemaDocWriter`, generating this module's `SCHEMA.md` on demand
    (`gradlew :zc-progression:generateSchemaDocs`, guarded by `SchemaDocDriftTest`). No router.
  - `progress/gate/` - `GateClause`/`GateSpec`/`GateKind`/`GateKindRegistry`/`GateEvaluator`/
    `GateValidator`/`GateRefusal`, the ONE requirement model behind every `Requires` block. Has its own router;
    read it before touching the gate schema or adding a `GateKind`.
  - `progress/runtime/` - THE shared progression runtime: one `QuestEngine` + `AchievementEngine`
    pair per server however many mods contribute to it. `ProgressionRuntime` (the holder),
    `ProgressionRegistrar` (what a consumer calls), `ProgressionParts`,
    `ContentLayers`, `ProgressionSubjectSource`, `ProgressionSystemGate`/`ProgressionSystem` (an
    owner's per-player "quests off" switch, contributed and ANDed), `ProgressionCallScope`,
    `ProgressionTextSource`, `ProgressionFeedbackHook` (the contributed reaction seam both engines
    announce their lifecycle moments through, so a toast, a jingle or a broadcast is somebody
    else's job entirely),
    and `ProgressionFactors` (the four `ziggfreedcommon:` readings OF that runtime - finished quest,
    completion count, earned achievement, points total - claimed process-wide so any content
    anywhere can gate on progression with no Java and no edge to this module).
    Has its own router; read it before touching runtime registration.
- [`quest/`](src/main/java/com/ziggfreed/common/quest/CLAUDE.md) (+ `quest/asset/`,
  `quest/event/`) - the QUEST lifecycle: accept/track/hand-in/claim/cooldown, ships zero content,
  enforced by `QuestModuleAgnosticismTest`. `quest/asset/` is the authoring layer (native `Parent`
  inheritance, `Server/ZiggfreedCommon/Quests/`, the generator, the pool + validator); `quest/event/`
  is six native `IEvent<Void>` POJOs (the pin event included).
- [`achievement/`](src/main/java/com/ziggfreed/common/achievement/CLAUDE.md) (+
  `achievement/asset/`, `achievement/event/`) - the ALWAYS-ON peer lifecycle: nothing is accepted,
  nothing is abandoned, every criterion listens from the first event. Criterion progress is keyed
  by the criterion's ID (`"<id>#<critKey>"`, the authored `Criteria` map key - exactly like a
  quest's `Objectives`). `achievement/asset/` also carries the taxonomy types
  `AchievementCategoryAsset`/`AchievementMilestoneAsset` (`Server/ZiggfreedCommon/
  AchievementCategories/` and `/AchievementMilestones/`, the display taxonomy behind the shared
  `Listing.Category` leaf and the points ladder `zc-objectives` publishes into the runtime);
  `achievement/event/` is three native `IEvent<Void>` POJOs.

## Shipped resources

`Server/Languages/<bcp47>/ziggfreedcommon.progress.lang`, 9 locales (de-DE, en-US, es-ES, fr-FR,
hu-HU, it-IT, pt-BR, ru-RU, tr-TR): the neutral objective sentence family
`NeutralObjectiveComposer` falls back to, the shared lock-reason lines, and the names the factor
overlays point at. Plus `Server/ZiggfreedCommon/Factors/Progression_{Quest_Completed,
Achievement_Earned}.json`, the two naming overlays that let `ziggfreedcommon:quest_completed` and
`ziggfreedcommon:achievement_earned` read as a sentence in a requirement line with no Java.

`SCHEMA.md` (repo-committed at this module's root) is the authoring reference for
the quest and achievement asset types, regenerated on demand from the actual codecs, deliberately
NOT wired into `processResources` since it is documentation rather than a jar resource -
`SchemaDocDriftTest` fails the build if the committed file drifts from the codecs.

## Conventions

Both engines ship ZERO content and ZERO domain vocabulary; a consumer supplies storage
(`QuestProgressStore`/`AchievementProgressStore`, plus ready-made in-memory ones), gates, and
naming. A surface that only READS a player's quests (a dialogue condition, above all) takes the
narrow `QuestStateReader` seam rather than the mutating engine - and so does anything reading
progression as a NUMBER: `ProgressionFactors` declares its own narrow read seam for exactly that
reason, so a gate on "have you finished this" can never be a gate that accepts it. Achievement
criteria are keyed by ID: renaming a key starts that criterion over, while adding, removing or
reordering entries never moves anyone's progress - asserted directly in the engine test.

## Tests

43 files: the shared cores (`ObjectiveKindRegistryTest`, `ObjectiveMatchTest`,
`ObjectiveProgressStateTest`, `ContentMetaTest`), `NeutralObjectiveComposerTest` and
`ProgressionFactorOverlaysTest` (the library's own shipped sentence family and the naming overlays
for its two progression factor ids), the quest engine (`QuestEngineFlowTest`,
`QuestEngineTurnInTest`, `QuestLifecycleTest`, `RepeatEvaluatorTest` (the ONE repeat evaluator, and
where the "the repeat rules count FINISHES, not collections" half of the completion record is
pinned), `CompletionRecordTest` (the record's own invariants, the collected-clamp above all),
`QuestGateTest`, `QuestGeneratorTest`, `LockReasonsTest` (the token-to-line decision every locked
surface reads), `QuestAssetCodecTest`, `QuestPoolValidatorTest`, `QuestStateReaderTest`, `QuestNestedIdTest`,
`QuestResetsTest`, `QuestCompleteAtTest` / `QuestTurnInAtCodecTest` (where a quest may be collected),
`QuestProgressPayloadTest`, `QuestStatThresholdTest`, `QuestTrackedEventTest`, `RepeatPeriodTest`,
`NpcOfferProvidersTest`,
`QuestModuleAgnosticismTest`, `RequiresGatesTest` - the ONE gate both engines share: fail-open for
content that asks for nothing, one answer whichever layer folded it, the server-first claim, and the
live cap and availability seams), what each engine OWES a consumer's persistence
(`QuestEnginePersistenceReportTest` and `AchievementEnginePersistenceReportTest` - a re-arm, the two
"this quest is finished" rules and the runtime forwarder all report their own writes, while the
COMMIT half is pinned with exact counts: collecting commits exactly once, a payout commits only when
it delivered something, and earning, a reached milestone, a re-arm and a catalogue-wide self-heal
commit nothing at all), the achievement engine
(`AchievementEngineTest`, `AchievementListingTest`, `AchievementAssetCodecTest`, `AchievementPoolValidatorTest`,
`AchievementProgressStoreTest`, `AchievementStatThresholdTest`, `AchievementTaxonomyCodecTest`),
the shared runtime (`ProgressionRuntimeTest`, `FeatureLiftTest` - which top-level conditions lift
out of a `Requires` block onto the caller's hide axis and which stay, `ProgressionFeedbackHookTest` - which moments each
engine announces, exactly once, with the subject and the values in scope, plus the contribution
properties (a late hook still fires, two hooks both fire, a throwing one costs only itself),
`ProgressionFactorsTest` - each factor ladder over a
double AND over the real engines on in-memory stores, plus the leaf-and-factor agreement),
`ContentTextArgsTest` (an authored key's numbered slots reach the runtime object, for BOTH content
kinds), and `SchemaDocDriftTest` guarding the committed `SCHEMA.md`.
