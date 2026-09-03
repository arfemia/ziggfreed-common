# CLAUDE.md - `quest/` (module `zc-progression`)

A **consumer-agnostic quest / objective ENGINE**. It ships ZERO content and ZERO domain vocabulary: no progression system, no economy, no notion of what a reward is. A consumer supplies the vocabularies, the storage, the gates, and the inventory access; the engine owns matching, ordering, cooldowns, hand-ins, completion, payout bookkeeping, and the outbound events.

Module edges: `zc-core` (registry ledger + `SafeLog`) and `zc-loot` (the reward vocabulary). Package root `com.ziggfreed.common.quest` (+ `.event`, `.asset`).

**What a REWARD is does not live here.** `RewardSpec` / `RewardHandler` / `RewardKindRegistry` / `RewardGrants` are in [`loot/reward/`](../../../../../../../../zc-loot/src/main/java/com/ziggfreed/common/loot/reward/CLAUDE.md) (module `zc-loot`, one level BELOW this one), because a quest hand-in, an end-of-round payout and an achievement unlock must all grant through the SAME registered kinds - a consumer registers a kind once and authors it everywhere. This engine imports them; the edge never points back.

**The shared cores live one package over, in [`../progress/`](../progress/CLAUDE.md).** The objective vocabulary, the ONE forgiving matching rule, the objective + progress model, the hot-path index and the dispatch knobs are NOT quest property - they are what every lifecycle engine in this module is built on, and `quest/` is one such engine (the active-set one: accept, track, hand in, claim, cooldown). Read `progress/` first; only what is genuinely about the QUEST lifecycle belongs on this page. The direction is one-way: `quest/` uses `progress/`, never the reverse.

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
- **A giver listing asks `QuestEngine#isOfferable`, NEVER `isVisible`.** They answer different
  questions and a hidden quest is where they part: `Visibility#hidden` keeps content off an OPEN,
  browsable listing, while at the one character authored to hand it out there is no browsing going
  on. Filtering a giver's list on the open-listing read leaves that character standing silently
  beside the thing they exist to hand out, and a whole authored chain becomes unreachable with
  nothing anywhere reporting it. The giver read asks only whether the quest is switched on and
  whether the player is past what it asks for first, which is what an author means by "keep this out
  of sight until it is relevant".

## The hygiene rule that governs this whole package

`QuestModuleAgnosticismTest` (in this module's test tree) walks this module's `src/main/java` line by line and FAILS the build on a case-insensitive hit of any foreign progression vocabulary. Never write the name of a consumer mod, its id prefixes, or its domain concepts here - not in code, not in a comment, not in javadoc. Generic engine terms only. A quest carries free-form `tags` precisely so a consumer's own classification rides through the engine without the engine ever learning it. (The router you are reading is excluded from the scan, as is `src/test` - a fixture naming a concrete id while proving a generic mechanism is doing its job.)

## The pieces

| Class | What it is |
|---|---|
| `QuestEngine` (+ `.Builder`) | the runtime; every operation and every seam hangs off it. THE instance comes from [`../progress/runtime/`](../progress/runtime/CLAUDE.md); `builder()` is for tests and for a consumer that genuinely wants a private engine |
| `Quest` (+ `.Repeat`, `.Visibility`) | the RESOLVED quest definition an authoring layer produces; its objectives are `progress.ObjectiveDef`, and it CARRIES what the shared parts need to answer for it - the authored `Requires` block, the `progress.ContentText` words, the giver id, the listing order, `available` as a LIVE predicate, and `occupiesLog` |
| `RequiresGates` | ONE gate for BOTH engines, reading `requires()` off the runtime object. Registered by `zc-objectives`' defaults for the quest side AND the achievement side, so a consumer answering a `Requires` block a second way is answering it twice |
| `QuestTurnInSite` | WHERE a quest may be collected: a named character, or wherever this player took it from. Nullable on `Quest`, and its presence is the restriction |
| `QuestProgressPayload` | packs one quest's whole `progress.ObjectiveProgressState` map into the opaque string a store persists |
| `QuestStatus`, `QuestLifecycle` | the state machine, the effective-status rule, and `repeatCheck` - the ONE evaluator for whether a repeatable may be taken again |
| `RepeatPeriod` | the pure calendar arithmetic behind a `Repeat.Reset` window of ANY length - a day, a week, eight hours, a fortnight (UTC, `floorDiv`-indexed, saturating; the weekday start takes part only for a window that is a whole number of weeks) |
| `QuestCadence` | the ONE classification of how often a quest comes round (`NONE` / `REPEATABLE` / `DAILY` / `WEEKLY`) from the LONGER of the rolling cooldown and the calendar window, thresholds twenty hours and six days spelled once on the enum; `Repeat.cadence()` delegates to it, and every listing badge, achievement qualifier or rotation label reads it rather than bucketing on its own |
| `QuestProgressStore` (+ `.CompletionRecord`), `InMemoryQuestProgressStore` | THE persistence seam and a ready-made in-memory one |
| `QuestGates`, `QuestPossessionProbe`, `QuestInventoryConsumer`, `QuestI18n`, `progress.runtime.ProgressionFeedbackHook` | the consumer seams (the dispatch tap is shared: `progress.ProgressDispatchTap`; the feedback hook is a CONTRIBUTION, see [`../progress/runtime/`](../progress/runtime/CLAUDE.md)). `QuestGates` is FILLED by `RequiresGates` above: a consumer implementing it again is registering a second decision over one model. Accepting asks it ONE question, `opensFor`, which the default answers by asking `prerequisitesMet` and `accepts` in turn; a gate reading both off one requirement block overrides it and reads once |
| `QuestEngine.Builder#factors` / `#factorContext` | the OPTIONAL factor pair, wired the way a gate evaluator's is; unwired, `STAT_THRESHOLD` is purely consumer-fired |
| `QuestStateReader` | the narrow READ seam: what a conversation may ask, and the whole of what it may reach |
| `NpcOffer`, `NpcOfferProvider`, `NpcOfferProviders` | the open table answering "what is this character holding out to this player". `zc-objectives` ships the DEFAULT provider over the runtime catalogue - the giver id rides `Quest.npcViewId()`, so nothing else has to be registered for a character to have something to say |
| `LockReasons` | the ONE mapping from refusal tokens (`QuestGates`' flat lifecycle tokens, `progress.gate.GateEvaluator`'s structured ones) and `progress.gate.GateRefusal` records to the lines a player reads, shared by every locked surface (the book, the offer page, both commerce screens). A quest requirement names the quest (via `progress.runtime.ProgressionTexts`); a FACTOR requirement whose factor has a naming asset (`factor.FactorNames`) names the factor, with its bound when the caller holds the record (a `Min` of exactly 1 with no `Max` reads as the presence idiom, unnumbered) and with the `(currently N)` readout when the record carries the value the evaluation resolved; the two membership factor spellings (`ziggfreedcommon:quest_completed` / `achievement_earned` with a `Param`) name their content's own title exactly like the leaf forms (the quest one dedupes against a `Quests` leaf naming the same quest); a PERMISSION refusal reads its own fixed sentence (`lock.permission`, nine locales) whether spelled as the `Permission` leaf or as a `hytale:permission` factor condition (both dedupe to one line) - a missing permission is a different kind of answer from a numeric bound; a COMPOSITE record carrying children renders what its group asks for, each child through the same `line` recursion - an `AnyOf` as the either-or list (`lock.any_of` framing the routes joined by the translatable `lock.any_of.join`; ONE route reads as its own line, never a one-entry list; a multi-ask route is one comma-joined `ALL_OF` bundle), a passing `Not` as the negated sentence (`lock.not.met`, childless `lock.not`) - and the children fold through `Msg.cat`, NEVER `Msg.join`, because the fold lands in a `{0}` param position and a param renders only when it carries `rawText` or `messageId` (a bare join renders EMPTY there; the separator folds the same way); everything else folds to the generic requirements line, and a raw token is never shown. `lines`/`bestLine` (tokens) and `linesOf`/`line(GateRefusal)` (records) share one dedupe rule, and the flat requirements token drops whenever a specific line covers it |
| `QuestResets` | the outbound RE-ARM seam: who is told that a quest went back to pristine, for state declared to live and die with it that this module sits below and can never call |
| `event/` | the six native `IEvent<Void>` POJOs (accepted, objective progressed, completed, claimed, abandoned, TRACKED - a pin or unpin, fired only on a real change: `track` re-stamping a live pin says nothing, `pruneStaleTracked` says it once per pin it dropped) + the `QuestEvents` fire helper. `QuestEvents.publishTo` is where the fires GO: the engine bus by default; a host running the engine with no bus (a harness, a unit JVM) installs its own to observe them |
| the quest MOMENTS | `Quest_Objective_Progressed`, `Quest_Completed`, `Quest_Parked` and `Quest_Claimed`, announced from `fireObjectiveProgressed` / `fireCompleted` / `fireClaimed` through the feedback hook. UNCONDITIONAL: `nativeEvents` turns off the cross-mod event bus, never a server's own toasts. Completion is two ids rather than one flag because a quest that settled and one waiting to be collected want their own words and their own sound; `parked` rides both argument maps so a hook can tell the cases apart without reading meaning into the id, and a parked one also carries `reason` (`QuestEngine.PARKED_COLLECT` for a quest authored to be collected, `PARKED_NO_SPACE` when the consumer said the player cannot receive the rewards, `PARKED_AWAY` when the quest names a turn-in site and the player finished elsewhere) plus, for a quest collected somewhere in particular, `turnIn` (`character` / `accept_site`), so ONE authored file can word a full bag apart from a collect-it-later. `Quest_Claimed` carries `collected` (true when the player came back for a parked reward, false when the quest paid out the instant it finished) so a jingle for collecting never plays over the completion jingle. `fireCompleted` and `fireClaimed` both carry the quest's whole payout under `rewards` (a deferred `Supplier`, so a moment nobody authored never composes it), which is what lets an authored toast list what was, or waits to be, handed over |
| [`../progress/`](../progress/CLAUDE.md) | the shared cores: `ObjectiveDef`, the vocabulary, matching, `ObjectiveProgressState`, `ObjectiveIndex`, `DispatchOptions`, `ZoneRef` |
| [`asset/`](asset/CLAUDE.md) | the authoring layer: the quest + generator asset schemas, the pool, the validator |
| [`../achievement/`](../achievement/CLAUDE.md) | the PEER engine over the same cores: always-on criteria, no accepting and no cooldowns |

## Rules to keep

- **REVERSE-EDGE TRAP: this module may never import dialogue, presentation, or world.** It sits BELOW zc-dialogue in the graph, so `zc-progression -> zc-dialogue` is a cycle the moment the quest-aware dialogue conditions land, and `-> zc-presentation` / `-> zc-world` would box the same corner in later. Quests will absolutely want an NPC turn-in conversation, a results page, a waypoint on the map, and world-scoped objectives - **every one of those is a SEAM this module declares and a HIGHER module's bootstrap (`zc-objectives`' `ProgressionBootstrap` registers the quest-list host, its `DialogueBootstrap` installs `QuestResets`) or the consumer fills, never an import.** The first violation will not be noticed until it is an unbreakable cycle three modules deep, mid-rewrite. The same rule binds zc-loot, which sits below this module.
- **Matching is ONE forgiving rule** (a maintainer-approved reversal of the pre-release "both dialects stay" position, 2026-08-25): targets compare case-insensitively, an empty target matches everything, an empty qualifier matches only an unqualified event. The rule lives in `progress/ObjectiveMatch`; do not grow a flavor knob back.
- **Every engine path that MUTATES the store calls `store.markDirty(subject)` before it returns.** A consumer's persistence backend is driven entirely off that call (zc-objectives' default stores fan it out to `ProgressionDefaults.onProgressDirty`), so a write that skips it survives the session and reverts on the player's next hydrate, with nothing anywhere reporting it. Report it INSIDE the method that made the write rather than at each caller, especially for a PUBLIC one an authoring layer calls (`clearQuest`, `markCompleted`, `markUnclaimed`): the caller that forgets is the one that never learns. A pin is saved state like any other, so `track` / `untrack` / `pruneStaleTracked` report too, the last only when it actually dropped something. A duplicate notification is harmless; a missing one is invisible.
- **`store.flush(subject)` is a much narrower thing, and this engine has exactly THREE.** `claim` commits UNCONDITIONALLY - the player pressed the button, so the outcome sticks whether or not that quest had anything to hand over. `checkCompletion`'s auto-claim payout and `forceComplete` commit ONLY when the payout actually delivered something (`GrantOutcome.anyDelivered`), so a reward-less quest settling during a sweep costs a backend nothing. Everything else waits for the batch: a quest that PARKS has paid nothing yet (and commits later, when it is collected), and nothing inside `selfHeal`, a re-arm, `pruneStaleTracked` or a pin write ever commits. No single engine call commits twice. A fourth flush point needs that argued first - a commit per engine-decided moment is how a login turns into one database write per entry the player already had.
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
  there (matching what the gate's answer has always done), while the later re-check honours it. A
  consumer whose saved records ARE readable through its registered factor vocabulary needs no
  gate-side answer at all: the engine's own reading covers it, and that is the shape to prefer.
- **`available` and `maxActive` are the consumer's NUMBER and PREDICATE, never its decision.** The
  cap a player's quest log holds and whether a quest is switched on are both things only the consumer
  knows and both things that move while the server is up, so each is a live supplier
  (`Quest#available()`, `ProgressionRegistrar#maxActiveQuests(IntSupplier)`) and the refusal built on
  either - `unavailable`, `log_full` - stays here. A consumer re-checking one in its own gate is
  making the same refusal twice, and the two will disagree the day one is fixed.
- **The cap is measured against `logSlotsUsed`, not `activeCount`, and `Quest#occupiesLog()` is what
  separates them.** An errand a player picks up somewhere that keeps its OWN list - a board contract
  above all - is carried without ever appearing in a quest log, so counting it against the cap would
  take slots away from a player with nothing on their log screen to account for them AND refuse them
  the next errand for a log they are not filling. Both halves are the same switch, so it is one
  boolean rather than two. `activeCount` still answers "how many catalogued quests is this player
  carrying" and is not the cap read; a surface painting an "X of Y" header should ask
  `logSlotsUsed`, or its number and the engine's refusal will tell one player two things.
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
- **A `Reset` window is one LENGTH, not a daily/weekly enum.** `Quest.Repeat.Reset(periodMs,
  atMinutes, weekStart, times)`: the asset's `Every` duration group (or its `Period` Daily|Weekly
  sugar) folds to the length, `weekAligned()` says whether `weekStart` takes part (a whole number
  of weeks), and `RepeatPeriod` indexes the epoch grid with it. Two readings of one window would
  be two things that can disagree, so there is no enum beside the number; `QuestCadence` is the
  one bucketing, and it weighs this length against the rolling `cooldownMs`.
- **`CooldownFrom` is an ANCHOR, not a mode.** It names the single instant one clock counts from
  (`CLAIM` the reward being taken, `COMPLETE` the objectives being met) and toggles nothing else.
  `COMPLETE` is what a quest belonging to a rotating, period-based offer wants, so collecting late
  does not burn a slot in the next period. Do not reintroduce a consumer-specific check in its place.
- **"Where should I go next" and "may I hand this in" are TWO questions, and the engine answers both
  itself.** `readyToTurnInAt` is the DESTINATION read: the quest is genuinely ACTIVE and its first
  outstanding step resolves at this id - either a hand-in that may be handed in here, or a step whose
  KIND declares a place-typed target (`progress.ObjectiveKind#targetsPlace`) naming this place, whole
  id against whole id, ignoring case. A step whose kind targets a THING never resolves anywhere, and
  a blank target resolves nowhere rather than everywhere. `canDeliverTurnInAt` is that plus a live
  hand-in the player can satisfy, and a quest with NO outstanding hand-in is NOT deliverable however
  plainly this is where its next step happens: `attemptTurnIn` acts only on hand-in objectives, so
  answering yes would offer a delivery that provably does nothing while the step is credited by the
  conversation or the surface where the author put the beat. A caller MARKS on the weak one and
  OFFERS on the strict one.
- **WHERE a quest may be collected is ONE predicate, and the engine enforces it itself.**
  `canCompleteAt(subject, quest, atOrNull)` is the whole rule: no `Quest#turnInAt()` means anywhere
  (the default, and most content), a `CHARACTER` site answers to one id case-insensitively, an
  `ACCEPT_SITE` site answers to the place recorded when the player took it. `claim` and
  `checkCompletion` both AND it in, so a surface that never asks cannot pay a quest out in the wrong
  place; a quest bound to a place PARKS instead of auto-claiming when it finishes anywhere else, so a
  refusal never costs the player the reward. `forceComplete` ignores it exactly as it ignores the
  objectives, because setting the content's rules aside is what that call is for. Every surface ASKS
  the predicate rather than keeping a copy, and asks it through `QuestStateReader` when it only
  looks.
- **One character answering to several ids is resolved ABOVE this module.** `canCompleteAt` compares
  ONE id, and a caller holding a character's whole answer set asks once per id, exactly as the
  at-a-character reads beside it are already called. That is what keeps every identity registry out
  of a module that sits below the one owning them. The accepted-at form is matched by plain id
  equality and nothing else: what a player took a quest from need not be a character at all.
- **The accepted-at place rides INSIDE the progress payload.** `accept` takes a nullable site id and
  `QuestProgressPayload` carries it in the same opaque string the objective progress lives in, so
  every store persists it with no new field, no capability probe and no migration; a payload written
  without one reads back as no place. `saveProgress` re-reads and re-emits it rather than trusting
  every caller to thread it, because a save that dropped it would unbind the quest mid-run with
  nothing reporting it. A site id carrying one of the format's reserved characters is refused with
  one warning rather than stored in half.
- **`clearQuest` re-arms; it does NOT wipe the completion record.** Abandon and the off-cooldown
  reset both go through it, and a lifetime cap either of them wiped would be a cap nobody could ever
  reach. `setCompletions(..., CompletionRecord.NONE)` is the deliberate wipe, and `QuestEngine#wipeQuest`
  / `wipeAllQuests` are the ADMINISTRATOR's form of it: drop the record, then re-arm through
  `clearQuest` so the re-arm is still reported. An admin surface (the `/zigprogress` family, a
  consumer's alias) calls those rather than reaching for the store.
- **A `CompletionRecord` carries TWO tallies, finished and collected, and exactly two private
  methods on `QuestEngine` write them.** `recordCompletion` writes a FINISH, and raises the collected
  tally alongside it when the finish paid out in the same instant; `recordClaim` writes a COLLECTION
  onto a finish already recorded, and touches nothing else. Two public doors reach them and there are
  no others: `markUnclaimed` parks a finish uncollected, and `markCompleted` either records a
  straight-through finish-and-collect or, when the quest was already parked, the collection of it.
  That second door is the only place a collection is ever counted, which holds because every route to
  `COMPLETED` (the auto-claim path, the collect of a parked one, an administrator's close-out) pays
  the quest out immediately afterwards. `repeatCheck` reads the FINISH tallies while
  `ProgressionFactors`'
  completion COUNT reads the collected one, so a parked reward is not a run already done under either
  reading. No PLAYER can make those two disagree at a repeat decision: a parked quest is not offered
  and `canAccept` refuses it. A deliberate force can - an `accept` that skips the eligibility check on
  purpose (a scripted start, an administrator) or a re-arm that clears the parked status - and the
  FINISH is the safe half to cap on, because the run happened and spent its slot whether or not
  anybody came back for the reward. That is the whole of the agreement: the count is a LIFETIME
  tally and the flag is a CURRENT status, so a repeatable that has come back around reads 0 on the
  flag and N on the count, and a ONE-SHOT (no `Repeat` group, so no record is ever written) reads 0
  on the count whatever the flag says. A store decoding a record with no collected tally reads it as
  collected equal to finished, through the named `CompletionRecord.withoutCollectedTally` factory:
  those finishes were paid out under the rule the save was written under, and the factory is the only
  way to make that claim so a writer cannot make it by accident. Collected is CLAMPED to finished by
  the record itself, so such a value cannot be talked into counting its parked run twice when
  somebody finally collects it.
- **`QuestEngine#clearQuest` is the ONE way a quest is re-armed, and `store().clearQuest` is not a
  shortcut to it.** The engine's own form does the store's clear AND reports it through
  `QuestResets`, which is what a layer holding state declared to live only as long as that quest is
  waiting for - a conversation's memory of it above all. Reaching past the engine re-arms the quest
  and tells nobody, and the symptom does not appear until an author's content stops behaving with
  nothing anywhere saying why. Every caller inside this library goes through the engine (the
  authoring pool's completion resets, and a rotating offer putting a lapsed one back in reach).
  The seam itself is a courtesy in the same sense the outbound events are: guarded, on the caller's
  thread, and absent in a bare unit JVM.
- **A store says what it can hold.** `recordsCompletions()` is the honest capability probe, exactly
  like `usesReservedDelimiter`. A store that answers false leaves `Reset` and `MaxCompletions` inert,
  and `setQuests` says so ONCE per quest at load rather than letting the content quietly not work.
- **The payout never throws and never aborts.** `RewardGrants` (in `loot/reward/`) isolates each reward and converts a failure into a queued retry where the handler offers one. A caller reads `GrantOutcome` rather than assuming success.
- **The conversation side of that seam is REAL now.** `zc-dialogue`'s pre-seeded quest vocabulary
  (`QuestState` / `ReadyToTurnIn` / `HasReadyToTurnIn` conditions, `Accept` / `TurnIn` actions) is
  implemented against `QuestStateReader` plus a consumer-supplied answer set and two opt-in write
  methods, all bundled in `dialogue.quest.DialogueQuests`. The edge is one-way: this module may
  never import anything from `zc-dialogue`.
- **A surface that only LOOKS gets `QuestStateReader`, never the engine.** `QuestEngine` implements it, and a conversation - which decides what to SHOW, dozens of times per render - is handed the interface. The engine mutates; a read seam cannot, so no amount of drift turns a rendering pass into an accept or a claim. Keep the seam narrow: a method belongs there only if a dialogue genuinely asks it AND the runtime can answer it alone. "Does this place have anything to OFFER?" fails the second half (who hands out what is an authoring-layer association plus a gate pass), so it is answered by ASKING `NpcOfferProviders` rather than by widening this seam. `canCompleteAt` is on the seam for the same reason: a quest page, a book and a conversation all decide whether to OFFER a collection, and one predicate is what stops three copies of the rule drifting. It defaults to yes, since a reader modelling no places must not hide every collection in the game and cannot let a wrong one through either. **`resolvesTurnInAt` is the deliberate weaker twin of `canDeliverTurnInAt`**: a marker pointing a player at the character a step is going to, versus a button that completes it. The seam DEFAULTS to the possession-aware answer, which is the honest one for a reader that cannot tell the two apart and can only ever under-report a destination; `QuestEngine` CAN tell them apart, so it overrides the seam with its own `readyToTurnInAt` and a "go and speak to them" step marks the character it points at instead of leaving it unmarked.
- **Events are an outbound courtesy.** Every fire is guarded end to end; a listener blowing up must never take a completion down with it. Fire from the world thread. A pin event is part of that courtesy too: `zc-objectives`' tracked-quest HUD repaints off it, so a new pin write path that fired nothing would leave a player's tracker one pin behind.

## Adding to it

- A new objective kind: a consumer calls `progress.ObjectiveKindRegistry.register`. Only add to `BUILT_IN_ACCUMULATING` or `BUILT_IN_VALUE_BASED` (the ARITHMETIC split, so a kind lands in exactly one) when the kind is meaningful in ANY game with no assumptions; `BUILT_IN_PLACE_TARGETED` is orthogonal to both.
- A new reward kind: a consumer registers a `RewardHandler` on the shared registry in `loot/reward/` - it then works at every payout site, not just quests. Give it a `retryCommand` whenever the reward is replayable, or a failure is a real loss.
- A new seam: prefer widening an existing interface with a DEFAULT method over adding a builder knob nobody sets.
- Tests are mechanics, structure, and invariants only. Fixtures are author-owned; never assert numbers that belong to somebody's balance pass.
