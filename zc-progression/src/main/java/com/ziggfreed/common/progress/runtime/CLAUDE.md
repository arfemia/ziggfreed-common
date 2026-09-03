# `progress/runtime/` - THE shared progression runtime

One `QuestEngine` + `AchievementEngine` pair per server, however many mods contribute to it.

## The rule

There is ONE runtime, always. Nothing decides whether to build it; the only question is which parts
it was built over. A mod does not construct engines and hand them round - it REGISTERS its parts and
reads the pair back from `ProgressionRuntime`.

Two runtimes over two stores is double-tracking: one block break advances two copies of the same
objective, the player sees one of them, and the half a surface reads is not always the half that can
pay out. Registration makes that failure impossible per server rather than merely unlikely per mod.

`QuestEngine.builder()` / `AchievementEngine.builder()` stay public and stay the right tool for a
UNIT TEST and for a consumer that genuinely wants a private engine (a round that dies with the
match). They are the wrong tool for a mod that wants the server's progression.

## The pieces

| file | what it is |
|---|---|
| `ProgressionRuntime` | the holder: registration entry points, the engine pair, content publishing, `ensureBuilt`, the boot diagnostic |
| `ProgressionRegistrar` | what a consumer calls; fluent, idempotent, conflict-policed |
| `ProgressionParts` | the resolved snapshot + the FORWARDERS the engines are built over + gate/tap composition |
| `ContentLayers` | per-owner content layers and the merge, so a reload replaces one owner's layer |
| `ProgressionSubjectSource` | how a player becomes the subject the ACTIVE stores understand, and nothing else. Whether a SYSTEM is on for that player is a separate contribution, because a subject is also what a storefront, a board and a conversation are built over, so refusing to build one would take a wallet away with the quest log |
| `ProgressionSystemGate` + `ProgressionSystem` | the owner's own "quests off on this server" / "achievements off until launch" switches, per player and per system (`QUEST` / `ACHIEVEMENT`). A CONTRIBUTION: every gate is asked, they AND, none registered means open, and one that THROWS is read as OPEN with one warn. The COMPOSED gate reaches three places: every produced moment (`ProgressDispatch.fire`, through `systemEnabled`), and BOTH engines through their `systemGate` builder knob (`ProgressionParts.SYSTEM_GATE`, read live), so `QuestEngine.canAccept` refuses a switched-off player with `QuestGates.REASON_SYSTEM_DISABLED` standing alone, `autoAcceptAvailable` accepts nothing for them, and `AchievementEngine.selfHeal` earns nothing for them - the maintenance pass re-reads live standing values and would otherwise hand out every met achievement at login |
| `ProgressionCallScope` | what a consumer publishes around a mutating call, so a shared surface fires what its own menu would |
| `ProgressionFeedbackHook` | "this lifecycle moment just happened to this subject, and here is what was in scope" - a free-string moment id plus a `Map<String,Object>` of named values, so a reaction reads the consumer's own handle off the `Subject` instead of finding the player again off a uuid. A CONTRIBUTION: every hook sees every moment, each guarded, order irrelevant, and a moment announced into a fan-out NOBODY filled says so once through the warn sink rather than vanishing. Seven moments today, six from inside the engines: `Quest_Objective_Progressed`, `Quest_Completed`, `Quest_Parked`, `Quest_Claimed`, `Achievement_Unlocked`, `Achievement_Claimed`, plus `Achievement_Server_First_Lost` from `FirstClaims`. `ProgressionRuntime.feedback()` hands out the live fan-out for a moment the engines do not own |
| `MomentListener` + `Moment` + `MomentPayload` | "this just happened to this player" - a REACTION to a PRODUCED moment (a block broken, a mob killed, an item crafted / picked up / placed, or anything a fourth-party producer fires through `ProgressDispatch.fire`), carrying the tuple both engines get (kind, target, qualifier, amount, zone) PLUS what a reaction needs and an engine never does: the `Store` / `Ref` / producer `CommandBuffer` to write against, both subjects as resolved (either may be null), and the producer's own typed `MomentPayload` (an OPEN marker, never sealed; the records live beside the producers in zc-objectives). A CONTRIBUTION registered through `ProgressionRegistrar.momentListener`: every listener sees every moment, each guarded, order irrelevant, late registration fires (`ProgressionRuntime.momentListener()` is the live fan-out). **Fanned FIRST from `ProgressDispatch.fire`, before the subject test and both system gates, unconditionally** - a consumer's reaction is its own product, not a progression half, so a player with no quest subject and an owner with a system switched off still get it. **It is NOT the tap** (the tap says "an engine considered this", fires inside each engine after its subject and its gate, once per action) **and it is NOT a gate** (nothing a listener does can refuse a moment or stand a producer down) |
| `KillAttribution` | "this non-player attacker acts for THAT player" - the seam a kill producer asks before it credits nobody for a kill a turret, a summon or a pet landed, so the moment fires for the owner. A CONTRIBUTION registered through `ProgressionRegistrar.killAttribution`: every one asked in registration order, first non-null answer wins, a throwing one skipped with a warn, nothing registered means a non-player attacker credits nobody (`ProgressionRuntime.killAttribution()` is the live composed answer). Answer only ever a PLAYER's ref; the producer checks |
| `KillQualifier` | "this killed entity carries THAT qualifier" - the seam the kill producer asks ONCE at fire time, so the one primary `KILL_ENTITY` dispatch can carry a qualifier for the victim (e.g. a difficulty tier a companion mod attributes) and a criterion authoring it matches. A CONTRIBUTION registered through `ProgressionRegistrar.killQualifier`, the `KillAttribution` shape exactly: asked in registration order, first non-null answer wins, a throwing one skipped with a warn, nothing registered means every kill fires unqualified as before (`ProgressionRuntime.killQualifier()` is the live composed answer). Never a second qualified re-fire - the matching rule reads an empty AUTHORED qualifier as "any", so a re-fire would count one kill twice for every unqualified criterion |
| `ProgressionTextSource` | how a surface with no catalogue NAMES a piece of content. Its `lore` DEFAULT is the shared `quest.<id>.md.<state>` convention, so the narrative rule is one rule rather than one per source |
| `ProgressionTexts` | the ONE static walk over every registered text source, first non-null winning, each source guarded on its own - what a title, flavor line, step line or lore read is asked through on ANY shared surface (the book, the board page, the offer page, the tracked panel, a consumer's commands). `titleOrUntitled` / `objectiveOrUntitled` fall back to this module's own placeholder lines (`ziggfreedcommon.progress.untitled` / `.step.untitled`) for a slot that must show something |
| `ProgressionGates` | THE `GateEvaluator` and the ONE `RequiresGates` over it, built on first ask and holding no registration - the vocabulary, the context and the requirement kinds are read live off the runtime, so a surface asking during another mod's setup and one asking in play are on the same instance |
| `ProgressionFactors` | the five `ziggfreedcommon:` READINGS of this runtime (`quest_known` among them: catalogue presence, a definite 0 for an unknown id, no player needed), claimed process-wide so any content can gate on finished progression with no Java |

## Two shapes of registration, and they behave differently

- **one-slot** (store, subjects, factor context, scopes, ...): consumer beats library default silently;
  a SECOND consumer is REFUSED with a SEVERE naming both. Never resolve that silently.
  - **Wanting your own PERSISTENCE is not a reason to take the store slot.** `zc-objectives`'
    default stores fan `markDirty` / `flush` out to `ProgressionDefaults.onProgressDirty` /
    `onProgressFlush` (additive, every listener guarded) and read the component off a subject handle
    that merely ANSWERS for one, so a consumer with a database backend registers a hook and supplies
    its own subjects while the default stores stay THE store. Replace the store only when the state
    genuinely does not live in that component. The dirty fan-out covers every write an ENGINE makes
    onto that component - both adapters, pins and unpins included, the public re-arm and completion
    doors an authoring layer calls, plus the dialogue memories riding the same component - so the
    hook is a complete answer for anything reached through an engine; the objectives router lists the
    two around-the-engine doors that report for themselves (a direct component write, and a caller
    driving the store adapter itself).
  - **A new mutating engine path owes `markDirty`. It almost certainly does NOT owe `flush`.**
    There are exactly FIVE flush points across both engines and they are all a player-owned
    transaction closing: a quest collected (`QuestEngine.claim`), an achievement collected
    (`AchievementEngine.claim`), a milestone collected (`claimMilestone`) - those three
    unconditional - plus the auto-claim payout (`checkCompletion`) and an administrator's close-out
    (`forceComplete`), each only when a reward was actually delivered. Earning, reaching a
    milestone, a self-heal, a re-arm, a prune and a pin sweep all report dirty and wait for the
    batch, because they arrive in BULK and one commit apiece turns a login into a write per entry
    the player already had. Both engine routers (`quest/CLAUDE.md`, `achievement/CLAUDE.md`) carry
    the obligation, because that is where the code being added lives.
- **contribution** (gates, system gates, taps, moment listeners, kill attributions, kill
  qualifiers, feedback hooks, text sources): every registration
  applies. Gates AND with `accepts` collecting EVERY reason (no short-circuit),
  `preSatisfiedAmount` folding as a MAX; system gates AND with every gate asked, so registration
  order cannot decide the answer; taps, moment listeners and feedback hooks fan out, each
  individually guarded; kill attributions, kill qualifiers and text
  sources answer in order, first non-null wins.

The three shared VOCABULARIES are not registrar methods - `objectiveKinds()`, `rewardKinds()` and
`gateKinds()` hand out the live registries and a consumer registers into them. There is no slot to
conflict over, which is what a registry is for.

## Rules that bite

- **A producer always fires.** There is no claim and no stand-down: a consumer never registers a
  competing producer for a native event zc-objectives already covers, and a mod firing a NET NEW
  moment calls `ProgressDispatch.fire` from its own event system with no registration at all.
- **A consumer REACTS to a produced moment; it does not re-detect it.** What a consumer does when a
  block breaks or a mob dies (its own currency, a lifetime counter, a bonus roll) is a
  `MomentListener` registered here, never a second ECS system on the same native event beside the
  library's producer: two authorities on one event re-resolve the target, the zone, the placed guard
  and the owner twice and drift. The listener fires FIRST, before the subject test and both system
  gates, so nothing about either progression system can cost a reaction; and it can refuse nothing.
  A consumer that spawns things that fight for a player contributes a `KillAttribution` so the kill
  producer credits the owner, rather than keeping a kill system of its own for that case; a
  consumer that layers something onto the mobs themselves (a difficulty tier, a variant) contributes
  a `KillQualifier` the same way, so its answer rides the one kill moment as the qualifier instead
  of a second qualified dispatch existing beside it.
- **A surface asks the runtime for its subject.** Building one locally works on the server it was
  written against and silently drops every write on a server where another mod's store is active.
- **An owner's system switch is a GATE, not a producer claim, and gates STACK.** "Quests are off on
  this server" is a per-player, per-system question every producer honours equally: the producer
  still runs and still reaches the dispatch, and a refusal costs exactly the half it names. It is a
  contribution so two mods can each keep their own switch without either being able to re-open a
  system the other shut. **Default OPEN, and a gate that throws is read as OPEN too** - an
  unreadable switch must never turn a whole system off for every player on the strength of a bug,
  where failing open costs at most the one refusal that gate meant to make.
- **A surface wraps mutating calls in the registered scope**, or a claim from it pays out in silence
  while the same claim from the owning mod's own menu does everything.
- **Sealed parts** (`factors`, `maxTracked`, `maxPinned`, `rewardRetryQueue`) are read ONCE, when the
  engines are built. A late one is refused, loudly. An identical value is silent, which is why two
  mods agreeing costs nothing. `maxActive` is deliberately NOT sealed: it is an owner's config value
  a reload has to move, so it is read live and a consumer may register an `IntSupplier` for it.
- **There is ONE evaluator and ONE gate, and a consumer registers neither.** What a consumer
  registers is the factor VOCABULARY and the factor CONTEXT; `ProgressionGates` reads both live, so
  its answer is that consumer's answer everywhere without a second evaluator existing. A consumer
  building its own is the defect this arrangement exists to remove.
- **A feedback moment is announced UNCONDITIONALLY, unlike the native events.** The `nativeEvents`
  switch turns off the cross-mod event bus; it does not turn off a server's own toasts and jingles,
  which is what the hook carries. Every value goes into ONE map: a localized value as a `Message`
  and stays one, anything else as plain data, and a value nobody could supply is OMITTED rather than
  passed as null so a reader can tell "nothing to say" from "say this, and it is empty".
- **A moment fires off a state TRANSITION, never off a state being re-read.** Every engine path that
  announces one records the change first and returns early on the second ask: `Achievement_Unlocked`
  and `_Claimed` behind the status flip, `Quest_Completed`/`_Parked` behind `markCompleted` /
  `markUnclaimed`, `Quest_Objective_Progressed` only when a counter actually moved. This is what
  keeps a login quiet, because self-heal re-asks EVERY standing answer - and it runs on connect, on
  every world or instance entry, and whenever a surface opens. The one moment with no state behind
  it, `Achievement_Server_First_Lost` (a lost race deliberately records nothing, so the decision can
  be revisited), was announced by every one of those sweeps until it was told the occasion apart;
  see `achievement/UnlockOccasion`. A NEW moment with nothing recorded behind it needs the same
  answer before it ships.
- **An expensive value rides as a `Supplier` and a hook says whether it ANSWERS the moment.**
  `ProgressionFeedbackHook.answers(momentId)` defaults to yes (the honest answer for a hook that
  cannot tell), and a hook reading authored files knows for free - `ProgressionFeedbackHook.of(fire,
  answers)` pairs the two when they live in different modules, which is exactly the case for
  `zc-objectives`' `ProgressionBootstrap`, the registration that joins these engines to the
  authored feedback files. `fire` asks the question BEFORE it builds the argument map, so a moment nobody authored
  costs the engine nothing; that is what lets `Quest_Objective_Progressed` be announced on every
  block broken, with its title and its step sentence composed only when a reader exists. It is an
  optimisation and never a decision: answering yes and doing nothing is correct, answering no is a
  promise the producer takes at its word.
- **`Quest_Completed` and `Quest_Parked` are two moments, not one flag.** A quest that settled and
  one waiting to be collected somewhere want their own words and their own sound. Both carry
  `parked` in their arguments as well, so a hook handed either one can tell which case it is
  without reading meaning into the id it was called with; a parked one also carries `reason`
  (`collect` / `no_space` / `away`, the `QuestEngine.PARKED_*` tokens) and, for a quest collected
  somewhere in particular, `turnIn` (`character` / `accept_site`), so ONE authored file can say
  "your bags are full" and "collect it where you took it" as two cases of the same moment.
  `Quest_Claimed` and `Achievement_Claimed` carry `collected` (true when the subject came back for
  a reward that waited, false when it paid out as it finished). An achievement's two moments also
  carry whatever its fold attached under `Achievement.momentArgs`, beneath the engine's own names.
- **The engines are never rebuilt.** Everything they reach the world through is a forwarder over the
  parts snapshot, so late registration is live AND every cached engine reference stays valid.
- **A factor READ never builds the runtime.** `ProgressionFactors` answers nothing until
  `isBuilt()`, because a gate evaluated early - a placement sweep, a content audit - would otherwise
  seal every sealed part before the consumers that own them had registered. A moment of shut gates
  is recoverable; a boot that moved where player data lives is not.

## The readings, and why they are contributed rather than registered

`ProgressionFactors` turns this runtime into four ordinary factor ids
(`ziggfreedcommon:quest_completed` / `quest_completions` / `achievement_earned` /
`achievement_points`), claimed process-wide through `FactorContributions` by ONE `contribute()` call
from `zc-objectives`' `ProgressionBootstrap` at library setup. That is the shape because there is one progression per server and any number of
vocabularies reading it: a storefront, a board, an NPC placement, a conversation and a loot roll all
resolve the same ids without a single registration between them, and a consumer that genuinely wants
its own engine answered instead uses `registerInto` on its own registry, which always outranks a
contribution.

Three rules hold them honest, pinned by `ProgressionFactorsTest` - with the finish-counting half of
the third pinned by `RepeatEvaluatorTest`, which is where the repeat rules are actually driven, and
the clamp underneath it by `CompletionRecordTest`:

- **The reads are NARROW** (`Reads`: six questions, every one an answer about a player). A factor
  read may never reach a mutating engine - the same discipline `QuestStateReader` keeps for a
  conversation - so nothing here can accept, pay out or write.
- **An id nothing knows answers nothing.** Record first, catalogue second, then null. A typo must
  never read as "they have not done it", which would open a bounds-less gate authored to mean "only
  where that content exists". "Finished" is the stored status `COMPLETED` - the quest is done AND its
  reward has been collected - which is the same rule the `Requires` block's `Quests` prerequisite is
  answered by, so the two spellings cannot disagree. A quest waiting in `COMPLETED_UNCLAIMED`
  satisfies neither.
- **The COUNT means the same thing the flag does.** `quest_completions` reads the completion
  record's CLAIMED tally, so a run whose objectives are done and whose reward is still parked has not
  been done yet by either reading. The repeat rules (`MaxCompletions`, a calendar `Reset` allowance)
  deliberately keep counting FINISHES, and no PLAYER can make the two tallies disagree at a repeat
  decision: a parked quest is not offered and `canAccept` refuses it. A deliberate force can - an
  accept that skips the check on purpose (a scripted start, an administrator) or a re-arm that clears
  the parked status - and the finish is the safe half to count, because a run somebody walked away
  from without collecting still spent its slot. A record saved before the claimed tally existed
  reads back with claimed equal to finished, because those finishes were paid out under the rule
  they were written under, and the record clamps collected to finished so such a value cannot count
  its parked run twice. The agreement is about a PARKED reward and nothing wider: the flag is a
  current status and the count a lifetime tally, so a re-armed repeatable reads 0 and N, and a
  one-shot keeps no record at all and reads 0 on the count whatever the flag says.

## Where the defaults live

Not here. `zc-objectives`' `ProgressionDefaults` registers the library's own store, subjects, gate,
hand-in probes and text through this same public surface, at library-default rank. This package
therefore knows nothing about them, and there is no module edge in either direction.
