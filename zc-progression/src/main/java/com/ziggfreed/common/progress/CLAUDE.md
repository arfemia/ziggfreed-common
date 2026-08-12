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

Module edge: `implementation project(':zc-core')` only. Package root
`com.ziggfreed.common.progress`.

## The pieces

| Class | What it is |
|---|---|
| `ObjectiveDef` (+ `.Builder`) | one authored objective: kind, target, match mode, qualifier, amount, zone, order, hand-in lock |
| `ObjectiveKind`, `ObjectiveKindRegistry` | the open objective vocabulary; 21 engine-generic kinds pre-seeded, per consumer, never a shared mutable global |
| `StatThresholdProbe` | reads a `STAT_THRESHOLD` objective's stat channel for one subject, so an engine can settle a standing-value objective itself |
| `MatchFlavor`, `MatchMode`, `ObjectiveMatch` | the matching core - BOTH dialects, verbatim |
| `ZoneRef` | the zone / region an event happened in, for a zone-scoped objective |
| `ObjectiveProgressState` | how far along one objective is, plus its `"current/required"` wire form |
| `ObjectiveIndex` | the inverted kind -> objectives index the dispatch hot path walks |
| `DispatchOptions` | the two independent dispatch switches (`tapObservers`, `targetedOnly`) with three named combinations |
| `ProgressDispatchTap` | the side-channel that sees every tapped event, whether or not anything was listening - what a lifetime counter needs |
| [`asset/`](asset/CLAUDE.md) | the authoring GROUPS both engines share (text, objective leaves, reward entry, editor pick lists) |
| [`gate/`](gate/CLAUDE.md) | the ONE requirement model behind every `Requires` block in the module |

## The pre-seeded vocabulary

Twenty-one ids, every one producible. Twenty ACCUMULATE (`BREAK_BLOCK`, `PLACE_BLOCK`, `CRAFT_ITEM`,
`KILL_ENTITY`, `DEAL_DAMAGE`, `PICKUP_ITEM`, `TALK_TO_NPC`, `CATCH_FISH`, `TURN_IN`,
`COMPLETE_QUEST`, `TAKE_FALL_DAMAGE`, `PLAYER_DEATH`, `SPRINT_DISTANCE`, `SWIM_DISTANCE`,
`BREED_ANIMAL`, `FEED_ANIMAL`, `HARVEST_ANIMAL`, `COMPANION_COMBAT`, `REACH_LOCATION`,
`CONSUME_ITEM`): each names a MOMENT, a producer fires a delta, the tally grows.

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
exactly like the other twenty: a consumer that watches its own channel can dispatch it on change and
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
- **Both match dialects stay.** `STRICT` and `LENIENT` disagree on case sensitivity, on what an
  empty TARGET means, and on what an empty QUALIFIER means. Merging them silently changes what
  shipped content matches. `MatchFlavor`'s javadoc carries the argument; do not re-litigate it in
  code.
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
  assumptions. The two lists are the seeding split, so a kind lands in exactly one of them and
  `seedBuiltIns` needs no branch.
- Tests are mechanics, structure, and invariants only. Fixtures are author-owned; never assert
  numbers that belong to somebody's balance pass.
