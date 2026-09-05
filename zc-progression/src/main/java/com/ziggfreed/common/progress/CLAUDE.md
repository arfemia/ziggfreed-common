# CLAUDE.md - `progress/` (module `zc-progression`)

The **shared cores every lifecycle engine in this module is built on**: what an objective IS, the
vocabulary of moments that can advance one, how a fired moment is matched against one, how far along
a subject is, how the hot path finds the handful of objectives an event could possibly touch, and
the two switches a dispatch carries.

Two peer engines rest on this, not one: the QUEST lifecycle ([`../quest/`](../quest/CLAUDE.md) -
an active set a subject accepts, progresses, hands in, claims and re-earns on a cooldown) and the
ACHIEVEMENT lifecycle ([`../achievement/`](../achievement/CLAUDE.md) - always-on criteria that need
no accepting). They are peers over one set of cores, deliberately NOT unified - lifecycle
special-casing would thread through everything.

Module edges: `implementation project(':zc-core')` plus `implementation project(':zc-loot')` (the
reward VOCABULARY `progress/asset/` and `progress/runtime/` read). Package root
`com.ziggfreed.common.progress`.

## Where THE runtime lives

[`runtime/`](runtime/CLAUDE.md) holds the one shared `QuestEngine` + `AchievementEngine` pair a
server runs and the surface a consumer registers its own parts through. A mod does not build engines
and hand them round; it registers and reads the pair back. `builder()` on either engine stays the
right tool for a unit test and for a genuinely private engine, and the wrong one for a mod that wants
the server's progression.

## The pieces

| Class | What it is |
|---|---|
| `ObjectiveDef` (+ `.Builder`) | one authored objective: kind, target, match mode, qualifier, amount, zone, order, hand-in lock |
| `ObjectiveKind`, `ObjectiveKindRegistry` | the open objective vocabulary plus the ten INDEPENDENT facts a kind carries (`valueBased` which arithmetic a dispatch uses, `atMost` whether a value-based kind reads its amount as a CEILING instead of a target, `producible` whether content may author it, and seven saying what its TARGET names: `targetsPlace` somewhere to go, `targetsItem` something holdable, `targetsEntity` a creature, `targetsCurrency` a wallet, `targetsContent` another quest or achievement, `targetsBoard` a notice board, `targetsEncounter` a boss fight by its script id - the last six are what let a surface DRAW a step), plus a `Presentation` (the wording key, the fallback picture, the per-target pictures) so ONE thing describes a kind; 26 engine-generic kinds pre-seeded, per consumer, never a shared mutable global. `asset/ObjectiveKindAsset` is the same description written as a file, merged leaf by leaf over the Java registration by `asset/ObjectiveKindFold` |
| `ObjectiveArithmetic` | the ONE compare both engines run when a moment reaches an objective: accumulate, raise a high-water mark, or - for a value-based kind that is also `atMost` - treat the authored amount as a CEILING met the first time a fired value comes in at or under it. A ceiling records a binary `0/1` rather than the value, so a zero ceiling ("no deaths") is not born completed the way a zero count would be. Both engines mint states through `fresh`/`stored` and apply through `apply` (a produced moment) or `applyStanding` (a value an engine read for itself), so the third reading exists in exactly one place |
| `StatThresholdProbe` | reads a `STAT_THRESHOLD` objective's stat channel for one subject, so an engine can settle a standing-value objective itself |
| `MatchMode`, `ObjectiveMatch` | the matching core - ONE forgiving rule |
| `ZoneRef` | the zone / region an event happened in, for a zone-scoped objective |
| `ZoneLocator` | WHERE a player is right now, as that `ZoneRef`, read off the engine's own `WorldMapTracker`. ONE authority, because an event dispatched with no zone never matches an objective that names one: a caller that forgets to resolve one does not lose precision, it silently switches that content off |
| `ObjectiveProgressState` | how far along one objective is, plus its `"current/required"` wire form |
| `ObjectiveIndex` | the inverted kind -> objectives index the dispatch hot path walks |
| `DispatchOptions` | the two independent dispatch switches (`tapObservers`, `targetedOnly`) with three named combinations |
| `ProgressDispatchTap` | the side-channel that sees every tapped event, whether or not anything was listening - what a lifetime counter needs |
| `ContentText` (+ `.Builder`) | what a piece of content is CALLED, carried on the runtime object every fold builds: title / flavor keys with their convention twins and bound args, a per-step key, per-state lore, and a per-step LINE a fold may compose for a step that authored no key. KEYS, not sentences - the one composed leaf is that step line, and it is a `Supplier` because whatever composes it is often installed later in a boot than the catalogue is folded. A name falls back to whichever key was written when nothing resolves one, so a row is traceable to its file rather than blank. It is what lets ONE text source answer for content authored in any format |
| `ObjectiveComposer` | how one authored step reads as a SENTENCE: the consumer-installable slot (`install`, once at setup) over the library's own neutral family, asked through the guarded static `line(objective, authoredKey)` the folds stamp per step (lazily, as the supplier `ContentText` carries - a composer installs after a catalogue folds). An installed composer that throws costs only its fancier wording; the neutral family answers next, and a full miss answers null so the authored-key rung takes over |
| `NeutralObjectiveComposer` | the shipped sentence family, a four-rung ladder: (1) the step's own authored key, resolved WITH `{0}`=amount, `{1}`=target name, outranking everything generated; (2) the step's REGISTERED KIND's own `Presentation.TextKey` when the shared vocabulary (`ProgressionRuntime.objectiveKinds()`) knows the kind and it named one, resolved namespace-agnostically through `i18n.ContentKeys` exactly like rung 1, with a `.any` twin for a targetless step - a kind file, not this class, is the schema authority for its own wording (rpg-stations' `Work_Station.json`/`Station_Output.json` are the first owners); (3) the library's own convention key on the KIND id (`ziggfreedcommon.progress.objective.<kind>` + `.any` twin, all nine locales in this module's `ziggfreedcommon.progress.lang`) so a pack-added kind with no `TextKey` still joins by shipping one key - never a closed switch; (4) `objective.default` (+ `.any`), the last shipped resort. Qualifier and place wrap whatever composed through their own shipped lines; the target's name comes from the engine's shipped catalogues (`i18n.NativeNames.targetNameMsg`), a value-threshold's channel from the factor naming assets (`factor.FactorNames`, the `hytale:stat` overlay) - no vocabulary of its own, and a bare asset id is never painted at a player |
| [`asset/`](asset/CLAUDE.md) | the authoring GROUPS both engines share (text, objective leaves, reward entry, editor pick lists) |
| [`gate/`](gate/CLAUDE.md) | the ONE requirement model behind every `Requires` block in the module |

## The pre-seeded vocabulary

Twenty-six ids, every one producible. Twenty-five ACCUMULATE (`BREAK_BLOCK`, `PLACE_BLOCK`,
`CRAFT_ITEM`, `KILL_ENTITY`, `DEAL_DAMAGE`, `PICKUP_ITEM`, `TALK_TO_NPC`, `CATCH_FISH`, `TURN_IN`,
`COMPLETE_QUEST`, `TAKE_FALL_DAMAGE`, `PLAYER_DEATH`, `SPRINT_DISTANCE`, `SWIM_DISTANCE`,
`BREED_ANIMAL`, `FEED_ANIMAL`, `HARVEST_ANIMAL`, `COMPANION_COMBAT`, `REACH_LOCATION`,
`CONSUME_ITEM`, `ENCOUNTER_DEFEATED`, `ENCOUNTER_PHASE`, `ENCOUNTER_ATTEMPT`, `INSTANCE_ROUND_WON`,
`INSTANCE_ROUND_ENDED`): each names a MOMENT, a producer fires a delta, the tally grows.

`INSTANCE_ROUND_WON` and `INSTANCE_ROUND_ENDED` describe a finished instance ROUND and share one
contract: `Target` is `<modId>:<modeId>` (so the prefix `kweebec:` matches any mode of that mod),
`Qualifier` is the preset id, `Amount` is 1 per round. `INSTANCE_ROUND_ENDED` fires per PARTICIPANT
on every completion and `INSTANCE_ROUND_WON` per WINNER only on a win, so "play ten rounds" and "win
ten rounds" are two objectives rather than one with a flag. Fed by zc-objectives'
`ZigInstanceRoundProducer` off zc-instance's `InstanceRoundCompletedEvent`.

The three `ENCOUNTER_*` ids describe a boss fight and share their own contract: `Target` is the
encounter SCRIPT id, never the boss creature's id, so a step naming the boss holds through an
in-place role swap mid-fight, and `Amount` is 1 per fire. `ENCOUNTER_DEFEATED` fires once per
CREDITED participant when the boss falls, `ENCOUNTER_ATTEMPT` once per participant whenever a fight
SETTLES, on a defeat and on a wipe alike, and `ENCOUNTER_PHASE` for every live member on each phase
beat. The qualifier is the run's difficulty label for the two settlement kinds and the phase's own
state name for the phase kind. Fed by zc-objectives' `ZigEncounterProducer`, which listens to the
three settled beats and deliberately never to a reset, so a reload or a world unload credits nobody.

**Two of the twenty-six also declare `targetsPlace`**, `TALK_TO_NPC` and `REACH_LOCATION`: their
TARGET names somewhere a player can stand rather than something an event carries, which is what lets
a surface say "this step resolves HERE" about a step with no hand-in of its own. The facet is
orthogonal to the arithmetic - a kind accumulates or tracks a value, and independently does or does
not point at a place. `TURN_IN` is deliberately not one of them: what its target names is the thing
being delivered, and where it may be delivered is its own `turnInLockId`.

**Seven declare `targetsItem` and seven `targetsEntity`**, the two facets that let a surface DRAW a
step: an item id is a picture of itself, a creature id is that creature's own generated portrait.
They are orthogonal to `targetsPlace` and to each other, so `TALK_TO_NPC` is both a place and a face,
and `TURN_IN` names the item being delivered. Four more facets sit beside them. `targetsContent` is
seeded here on `COMPLETE_QUEST`, because this module owns both catalogues and can answer that
picture itself, and `targetsEncounter` on the three `ENCOUNTER_*` ids, whose target is a fight's
script id rather than a creature. `targetsCurrency` and `targetsBoard` are seeded on nothing: this
module defines neither a wallet nor a board, so the flag exists for a consumer's own kind to set and
for whoever owns currencies or boards to answer the picture - which is the whole division, the kind
carrying the fact and the vocabulary's owner carrying the reading.

`STAT_THRESHOLD` is the one VALUE-BASED built-in, and the one that names a STATE rather than a
moment:

| leaf | what it means |
|---|---|
| `Kind` | `STAT_THRESHOLD` |
| `Target` | a native stat CHANNEL id (the `hytale:stat` vocabulary, one registered entity stat type). REQUIRED: both content validators warn on a blank one, because there is nothing to measure without it |
| `Amount` | the threshold the channel has to reach |
| progress | the HIGH-WATER of the channel's effective (folded) value, so a reading that later drops back never takes recorded progress with it |

Because it is a state, nothing may ever fire to announce it: the value that satisfies it is usually
reached long before the content asking about it exists. So both engines can also read it THEMSELVES
through `StatThresholdProbe`, wired by the optional `factors(...)` + `factorContext(...)` pair on
either builder. The checkpoints are deliberately few and each rides on work already being done:

| engine | when it re-reads |
|---|---|
| quest | accept (folded into the same pre-satisfied seed the gate feeds), `selfHeal`, and off the back of a dispatch that delivered progress to the SAME quest |
| achievement | `selfHeal` only - see that engine's router for why the dispatch piggyback would not be cheap there |

There is NO poll behind any of it. Leave the pair unwired and the kind is purely consumer-fired,
exactly like the other twenty-two: a consumer that watches its own channel can dispatch it on change and
the high-water arithmetic is identical either way.

## Rules to keep

- **An unresolvable reading contributes NOTHING, and nothing is never a reset.** A blank target, an
  unregistered factor, a provider that cannot answer, a provider that throws: all read as `0`, and
  `0` applied as a high-water value is a no-op. That is what makes a re-check safe to run anywhere -
  a channel that has gone missing or a subject that is not loaded cannot roll a player back.
- **Nothing here may name a lifecycle.** No `Quest*` type, no achievement type, no import from
  `../quest/`. The moment a core needs one, it was not a shared core - leave it with the engine that
  wanted it. `ObjectiveIndex.of` is the shape to copy when a core needs owner data: take the owners
  plus accessors, never the owner TYPE.
- **No consumer vocabulary either**, same as the rest of this module: the agnosticism test walks
  every source file here. Generic engine terms only.
- **Matching is ONE rule, and it is the forgiving one** (a maintainer-approved reversal of the
  pre-release "both dialects stay" position, 2026-08-25, taken while nothing shipped depended on
  the strict one): targets compare case-insensitively, an empty target matches EVERYTHING under
  every mode, and an empty qualifier matches only an unqualified event. `ObjectiveMatch`'s javadoc
  states the rule; do not grow a second dialect or a per-engine flavor knob back.
- **A PLACE is compared as one whole id against one whole id, never through a match dialect.**
  `targetsPlace` exists so a reader can ask "does this step point HERE", and that comparison is a
  case-insensitive whole-id equality plus a non-blank target, nothing else. `MatchMode` stays what it
  is - the comparison a fired EVENT is matched with, where a target is deliberately written to
  catch a FAMILY of ids - and a family written to catch block ids would catch character ids too,
  while a blank target would point every such quest at every place at once.
- **Orthogonal knobs, never modes.** `DispatchOptions` is two independent booleans with three named
  factories. A new combination must never need a new constant.
- **The wire forms are byte-stable.** A consumer's store may persist `ObjectiveProgressState`'s
  `"current/required"` verbatim. Changing the spelling is a data migration, not a refactor.
- **A subject is [`subject.Subject`](../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/subject/Subject.java)** (zc-core) wherever one is needed here - one identity
  vocabulary under every engine.

## Adding to it

- A new shared core lands here only when BOTH lifecycle engines want it. One engine wanting it means
  it belongs to that engine. `ProgressDispatchTap` is the worked example: it arrived quest-named, and
  moved here the moment the achievement engine wanted the same side-channel.
- A new objective kind is a consumer call to `ObjectiveKindRegistry.register`; only add to
  `BUILT_IN_ACCUMULATING` / `BUILT_IN_VALUE_BASED` when the kind is meaningful in ANY game with no
  assumptions. Those two lists are the ARITHMETIC split, so a kind lands in exactly one of them;
  `BUILT_IN_PLACE_TARGETED` / `BUILT_IN_ITEM_TARGETED` / `BUILT_IN_ENTITY_TARGETED` /
  `BUILT_IN_CONTENT_TARGETED` / `BUILT_IN_ENCOUNTER_TARGETED` are orthogonal to both and to each
  other, naming whichever of them point somewhere, name something holdable, name a creature, name
  another piece of content, or name a boss fight.
  A consumer contributing its own vocabulary skips every id `ObjectiveKindRegistry.isBuiltIn` already
  names, so a built-in is described once, here, with every flag it carries - including one this class
  learns to seed after that consumer was written.
- Tests are mechanics, structure, and invariants only. Fixtures are author-owned; never assert
  numbers that belong to somebody's balance pass.
