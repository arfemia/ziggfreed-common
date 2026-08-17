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
| `ObjectiveKind`, `ObjectiveKindRegistry` | the open objective vocabulary plus the three INDEPENDENT facts a kind carries (`valueBased` which arithmetic a dispatch uses, `producible` whether content may author it, `targetsPlace` whether its target names somewhere to go); 23 engine-generic kinds pre-seeded, per consumer, never a shared mutable global |
| `StatThresholdProbe` | reads a `STAT_THRESHOLD` objective's stat channel for one subject, so an engine can settle a standing-value objective itself |
| `MatchFlavor`, `MatchMode`, `ObjectiveMatch` | the matching core - BOTH dialects, verbatim |
| `ZoneRef` | the zone / region an event happened in, for a zone-scoped objective |
| `ZoneLocator` | WHERE a player is right now, as that `ZoneRef`, read off the engine's own `WorldMapTracker`. ONE authority, because an event dispatched with no zone never matches an objective that names one: a caller that forgets to resolve one does not lose precision, it silently switches that content off |
| `ObjectiveProgressState` | how far along one objective is, plus its `"current/required"` wire form |
| `ObjectiveIndex` | the inverted kind -> objectives index the dispatch hot path walks |
| `DispatchOptions` | the two independent dispatch switches (`tapObservers`, `targetedOnly`) with three named combinations |
| `ProgressDispatchTap` | the side-channel that sees every tapped event, whether or not anything was listening - what a lifetime counter needs |
| `ContentText` (+ `.Builder`) | what a piece of content is CALLED, carried on the runtime object every fold builds: title / flavor keys with their convention twins and bound args, a per-step key, per-state lore, and a per-step LINE a fold may compose for a step that authored no key. KEYS, not sentences - the one composed leaf is that step line, and it is a `Supplier` because whatever composes it is often installed later in a boot than the catalogue is folded. A name falls back to whichever key was written when nothing resolves one, so a row is traceable to its file rather than blank. It is what lets ONE text source answer for content authored in any format |
| [`asset/`](asset/CLAUDE.md) | the authoring GROUPS both engines share (text, objective leaves, reward entry, editor pick lists) |
| [`gate/`](gate/CLAUDE.md) | the ONE requirement model behind every `Requires` block in the module |

## The pre-seeded vocabulary

Twenty-three ids, every one producible. Twenty-two ACCUMULATE (`BREAK_BLOCK`, `PLACE_BLOCK`,
`CRAFT_ITEM`, `KILL_ENTITY`, `DEAL_DAMAGE`, `PICKUP_ITEM`, `TALK_TO_NPC`, `CATCH_FISH`, `TURN_IN`,
`COMPLETE_QUEST`, `TAKE_FALL_DAMAGE`, `PLAYER_DEATH`, `SPRINT_DISTANCE`, `SWIM_DISTANCE`,
`BREED_ANIMAL`, `FEED_ANIMAL`, `HARVEST_ANIMAL`, `COMPANION_COMBAT`, `REACH_LOCATION`,
`CONSUME_ITEM`, `INSTANCE_ROUND_WON`, `INSTANCE_ROUND_ENDED`): each names a MOMENT, a producer fires
a delta, the tally grows.

The last two describe a finished instance ROUND and share one contract: `Target` is
`<modId>:<modeId>` (so the prefix `kweebec:` matches any mode of that mod), `Qualifier` is the preset
id, `Amount` is 1 per round. `INSTANCE_ROUND_ENDED` fires per PARTICIPANT on every completion and
`INSTANCE_ROUND_WON` per WINNER only on a win, so "play ten rounds" and "win ten rounds" are two
objectives rather than one with a flag. Fed by zc-objectives' `ZigInstanceRoundProducer` off
zc-instance's `InstanceRoundCompletedEvent`.

**Two of the twenty-three also declare `targetsPlace`**, `TALK_TO_NPC` and `REACH_LOCATION`: their
TARGET names somewhere a player can stand rather than something an event carries, which is what lets
a surface say "this step resolves HERE" about a step with no hand-in of its own. The facet is
orthogonal to the arithmetic - a kind accumulates or tracks a value, and independently does or does
not point at a place. `TURN_IN` is deliberately not one of them: what its target names is the thing
being delivered, and where it may be delivered is its own `turnInLockId`.

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
- **A PLACE is compared as one whole id against one whole id, never through a match dialect.**
  `targetsPlace` exists so a reader can ask "does this step point HERE", and that comparison is a
  case-insensitive whole-id equality plus a non-blank target, nothing else. `MatchMode` stays what it
  is - the dialect a fired EVENT is matched with, where a target is deliberately written to catch a
  FAMILY of ids - and a family written to catch block ids would catch character ids too, while a
  blank target would point every such quest at every place at once.
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
  `BUILT_IN_PLACE_TARGETED` is orthogonal to both and names whichever of them also point somewhere.
  A consumer contributing its own vocabulary skips every id `ObjectiveKindRegistry.isBuiltIn` already
  names, so a built-in is described once, here, with every flag it carries - including one this class
  learns to seed after that consumer was written.
- Tests are mechanics, structure, and invariants only. Fixtures are author-owned; never assert
  numbers that belong to somebody's balance pass.
