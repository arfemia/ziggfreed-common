# CLAUDE.md - `quest/` (module `zc-progression`)

A **consumer-agnostic quest / objective ENGINE**. It ships ZERO content and ZERO domain vocabulary: no progression system, no economy, no notion of what a reward is. A consumer supplies the vocabularies, the storage, the gates, and the inventory access; the engine owns matching, ordering, cooldowns, hand-ins, completion, payout bookkeeping, and the outbound events.

Module edges: `zc-core` (registry ledger + `SafeLog`) and `zc-loot` (the reward vocabulary). Package root `com.ziggfreed.common.quest` (+ `.event`, `.asset`).

**What a REWARD is does not live here.** `RewardSpec` / `RewardHandler` / `RewardKindRegistry` / `RewardGrants` are in [`loot/reward/`](../../../../../../../../zc-loot/src/main/java/com/ziggfreed/common/loot/reward/CLAUDE.md) (module `zc-loot`, one level BELOW this one), because a quest hand-in, an end-of-round payout and an achievement unlock must all grant through the SAME registered kinds - a consumer registers a kind once and authors it everywhere. This engine imports them; the edge never points back.

**The shared cores live one package over, in [`../progress/`](../progress/CLAUDE.md).** The objective vocabulary, the two match flavors, the objective + progress model, the hot-path index and the dispatch knobs are NOT quest property - they are what every lifecycle engine in this module is built on, and `quest/` is one such engine (the active-set one: accept, track, hand in, claim, cooldown). Read `progress/` first; only what is genuinely about the QUEST lifecycle belongs on this page. The direction is one-way: `quest/` uses `progress/`, never the reverse.

**Who a quest operation is about is [`subject.Subject`](../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/subject/Subject.java) (zc-core), not a quest-owned type.** One identity vocabulary under every engine, so a gate or a reward handler written for one reads naturally when another calls it.

**The core stays usable with hand-built models.** Everything about FILES - the `Server/ZiggfreedCommon/Quests/<id>.json` schema, native `Parent` inheritance, the quest generator, the `Requires` gate vocabulary, the content audit - lives one level down in [`asset/`](asset/CLAUDE.md) and hands this engine a plain `Collection<Quest>`. Nothing here may reach up into it.

## Offers: the one question the runtime cannot answer, asked instead of guessed

Which quests a place hands out is an authoring-layer association, and whether the player may take one
is a gate pass. Neither is quest STATE, so the engine has nothing to read - which is why every mod
grew its own private answer and no shared surface could render a giver's quests. `NpcOfferProviders`
is the open table that closes it: a mod registers where its catalogue and its gates actually are, and
the generic surfaces (a conversation's "have you anything for me" line, a fourth party's NPC panel,
an at-NPC encounter) ask. Several providers may answer at one character, which is exactly what makes
a server running two content mods work.

- **An `NpcOffer` is deliberately thin**: an id, an optional translation KEY (never rendered text -
  the player's client resolves it), an `available` flag, and opaque lock-reason keys. A LOCKED offer
  is still an offer: a giver that hides everything the player cannot have yet reads as having nothing
  to say, while one that shows the locked line is telling them what to go and do.
- **The lock reasons stay opaque.** Whatever decided an offer is unavailable - a level, an item, a
  prerequisite, another mod's gate entirely - is that mod's business; modelling it here would mean
  every gate system in every consumer agreeing on one shape.
- **A provider is handed the character's whole ANSWER SET**, primary first, so it matches against all
  of them rather than resolving aliases itself.
- `hasOffersAt` stops at the first yes because it runs wherever a surface decides whether to show a
  marker or a greeting - a per-character cost, not a per-click one.

## The hygiene rule that governs this whole package

`QuestModuleAgnosticismTest` (in this module's test tree) walks this module's `src/main/java` line by line and FAILS the build on a case-insensitive hit of any foreign progression vocabulary. Never write the name of a consumer mod, its id prefixes, or its domain concepts here - not in code, not in a comment, not in javadoc. Generic engine terms only. A quest carries free-form `tags` precisely so a consumer's own classification rides through the engine without the engine ever learning it. (The router you are reading is excluded from the scan, as is `src/test` - a fixture naming a concrete id while proving a generic mechanism is doing its job.)

## The pieces

| Class | What it is |
|---|---|
| `QuestEngine` (+ `.Builder`) | the runtime; every operation and every seam hangs off it. THE instance comes from [`../progress/runtime/`](../progress/runtime/CLAUDE.md); `builder()` is for tests and for a consumer that genuinely wants a private engine |
| `Quest` (+ `.Repeat`, `.Visibility`) | the RESOLVED quest definition an authoring layer produces; its objectives are `progress.ObjectiveDef` |
| `QuestProgressPayload` | packs one quest's whole `progress.ObjectiveProgressState` map into the opaque string a store persists |
| `QuestStatus`, `QuestLifecycle` | the state machine, the effective-status rule, and `repeatCheck` - the ONE evaluator for whether a repeatable may be taken again |
| `RepeatPeriod` | the pure calendar arithmetic behind a `Repeat.Reset` window (UTC, `floorDiv`-indexed, saturating) |
| `QuestProgressStore` (+ `.CompletionRecord`), `InMemoryQuestProgressStore` | THE persistence seam and a ready-made in-memory one |
| `QuestGates`, `QuestPossessionProbe`, `QuestInventoryConsumer`, `QuestI18n` | the consumer seams (the dispatch tap is shared: `progress.ProgressDispatchTap`) |
| `QuestEngine.Builder#factors` / `#factorContext` | the OPTIONAL factor pair, wired the way a gate evaluator's is; unwired, `STAT_THRESHOLD` is purely consumer-fired |
| `QuestStateReader` | the narrow READ seam: what a conversation may ask, and the whole of what it may reach |
| `NpcOffer`, `NpcOfferProvider`, `NpcOfferProviders` | the open table answering "what is this character holding out to this player" - the one question the quest runtime genuinely cannot answer alone |
| `event/` | the five native `IEvent<Void>` POJOs + `QuestEvents` fire helper |
| [`../progress/`](../progress/CLAUDE.md) | the shared cores: `ObjectiveDef`, the vocabulary, matching, `ObjectiveProgressState`, `ObjectiveIndex`, `DispatchOptions`, `ZoneRef` |
| [`asset/`](asset/CLAUDE.md) | the authoring layer: the quest + generator asset schemas, the pool, the validator |
| [`../achievement/`](../achievement/CLAUDE.md) | the PEER engine over the same cores: always-on criteria, no accepting and no cooldowns |

## Rules to keep

- **REVERSE-EDGE TRAP: this module may never import dialogue, presentation, or world.** It sits BELOW zc-dialogue in the graph, so `zc-progression -> zc-dialogue` is a cycle the moment the quest-aware dialogue conditions land, and `-> zc-presentation` / `-> zc-world` would box the same corner in later. Quests will absolutely want an NPC turn-in conversation, a results page, a waypoint on the map, and world-scoped objectives - **every one of those is a SEAM this module declares and the wiring root or the consumer fills, never an import.** The first violation will not be noticed until it is an unbreakable cycle three modules deep, mid-rewrite. The same rule binds zc-loot, which sits below this module.
- **Both match dialects stay.** `STRICT` and `LENIENT` disagree on case sensitivity, on what an empty TARGET means, and on what an empty QUALIFIER means. Merging them silently changes what shipped content matches. `MatchFlavor`'s javadoc carries the argument; do not re-litigate it in code. (It lives in `progress/`.)
- **The store is the only state.** The engine holds no per-player field. Anything that needs remembering goes through `QuestProgressStore`, and id hygiene is the STORE's call (`usesReservedDelimiter`) because only it knows its own format.
- **Orthogonal knobs, never modes.** `DispatchOptions` is two independent booleans with three named factories, not a mode enum; `Quest` composes behaviour from `Repeat` / `Visibility` / the auto-* switches rather than quest "types". Keep it that way - a new combination must never need a new constant.
- **A standing-value step is re-read at three points and NEVER polled.** `STAT_THRESHOLD` (see
  [`../progress/`](../progress/CLAUDE.md) for the kind's contract) names a state, so no producer may
  ever fire for it. `refreshStatThresholds` therefore runs where the cost is already paid: on
  `accept`, in `selfHeal`, and off the back of a dispatch that delivered progress to the SAME quest.
  Never add a timer or a sweep over every carried quest - the piggyback exists precisely so neither
  is needed, and it only ever reads OUTSTANDING, unlocked steps.
- **The accept-time seed is ONE number from two sources.** `preSatisfiedFor` takes the maximum of
  `QuestGates.preSatisfiedAmount` and the engine's own reading. That is not a tie-break: both are
  high-water values applied through the same `applyValue`, where applying two in turn leaves exactly
  the larger, so the max is the same result in one write. Ordering is deliberately not consulted
  there (matching what the gate's answer has always done), while the later re-check honours it.
- **`Quest#repeat()` is NULLABLE and its PRESENCE is the repeatable flag.** There is no boolean
  inside and no `NONE` sentinel: either would re-create the "it says false but the object exists"
  ambiguity. `quest.repeatable()` is the one-line read. An EMPTY group means externally governed -
  nothing on the quest holds it back, so whatever offers it decides when it comes round, and
  `selfHeal` re-arms it at once.
- **Three independent constraints, ONE evaluator.** A rolling `cooldownMs`, a calendar `Reset`
  allowance and a lifetime `maxCompletions` cap are ANDed by `QuestLifecycle.repeatCheck`, and the
  refusal a caller is told is chosen by ACTIONABILITY: the lifetime cap first (telling somebody to
  come back in three hours for a quest they can never take again is the worse message), then the
  spent window, then the running clock. Never add a fourth answer anywhere else - a surface wanting
  the specific reason asks `canAccept`.
- **`CooldownFrom` is an ANCHOR, not a mode.** It names the single instant one clock counts from
  (`CLAIM` the reward being taken, `COMPLETE` the objectives being met) and toggles nothing else.
  `COMPLETE` is what a quest belonging to a rotating, period-based offer wants, so collecting late
  does not burn a slot in the next period. Do not reintroduce a consumer-specific check in its place.
- **`clearQuest` re-arms; it does NOT wipe the completion record.** Abandon and the off-cooldown
  reset both go through it, and a lifetime cap either of them wiped would be a cap nobody could ever
  reach. `setCompletions(..., CompletionRecord.NONE)` is the deliberate wipe.
- **A store says what it can hold.** `recordsCompletions()` is the honest capability probe, exactly
  like `usesReservedDelimiter`. A store that answers false leaves `Reset` and `MaxCompletions` inert,
  and `setQuests` says so ONCE per quest at load rather than letting the content quietly not work.
- **The payout never throws and never aborts.** `RewardGrants` (in `loot/reward/`) isolates each reward and converts a failure into a queued retry where the handler offers one. A caller reads `GrantOutcome` rather than assuming success.
- **The conversation side of that seam is REAL now.** `zc-dialogue`'s pre-seeded quest vocabulary
  (`QuestState` / `ReadyToTurnIn` / `HasReadyToTurnIn` conditions, `Accept` / `TurnIn` actions) is
  implemented against `QuestStateReader` plus a consumer-supplied answer set and two opt-in write
  methods, all bundled in `dialogue.quest.DialogueQuests`. The edge is one-way: this module may
  never import anything from `zc-dialogue`.
- **A surface that only LOOKS gets `QuestStateReader`, never the engine.** `QuestEngine` implements it, and a conversation - which decides what to SHOW, dozens of times per render - is handed the interface. The engine mutates; a read seam cannot, so no amount of drift turns a rendering pass into an accept or a claim. Keep the seam narrow: a method belongs there only if a dialogue genuinely asks it AND the runtime can answer it alone. "Does this place have anything to OFFER?" fails the second half (who hands out what is an authoring-layer association plus a gate pass), so it is answered by ASKING `NpcOfferProviders` rather than by widening this seam. **`resolvesTurnInAt` is the deliberate weaker twin of `canDeliverTurnInAt`**: a marker pointing a player at the character a step is going to, versus a button that completes it. It DEFAULTS to the possession-aware answer, which can only under-report a destination and never offers an impossible hand-in - a runtime where a step can resolve at a character with nothing carried (a "go and speak to them" step is exactly that) overrides it.
- **Events are an outbound courtesy.** Every fire is guarded end to end; a listener blowing up must never take a completion down with it. Fire from the world thread.

## Adding to it

- A new objective kind: a consumer calls `progress.ObjectiveKindRegistry.register`. Only add to `BUILT_IN_IDS` when the kind is meaningful in ANY game with no assumptions.
- A new reward kind: a consumer registers a `RewardHandler` on the shared registry in `loot/reward/` - it then works at every payout site, not just quests. Give it a `retryCommand` whenever the reward is replayable, or a failure is a real loss.
- A new seam: prefer widening an existing interface with a DEFAULT method over adding a builder knob nobody sets.
- Tests are mechanics, structure, and invariants only. Fixtures are author-owned; never assert numbers that belong to somebody's balance pass.
