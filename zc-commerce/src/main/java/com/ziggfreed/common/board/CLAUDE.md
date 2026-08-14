# board/ - a board is a rotating VIEW over the quest pool (module `zc-commerce`)

Router for `com.ziggfreed.common.board`. A bounty IS a quest, and this is where that stops being a
slogan: nothing here keeps a second idea of what a contract is, when it was finished, or who is
carrying it. What a BOARD adds is which contracts are up right now, who may take which, and what a
reroll costs.

| Class | What it is |
|---|---|
| [`BoardEngine`](BoardEngine.java) | `activeSet` / `activeSetFor` / `canAccept` / `accept` / `completedThisPeriod` / `reArmLapsed` / `canReroll` / `reroll`, plus the refusal tokens |
| [`BoardSpec`](BoardSpec.java) | what the engine needs to know about one board: cadence, selection, slots, reroll terms, per-grade accept gates |
| [`BountyRef`](BountyRef.java) | what it needs to know about one bounty: its quest id, and three questions about ONE board |
| [`BoardQuests`](BoardQuests.java) | the narrow slice of the quest lifecycle the engine drives |
| [`QuestEngineBoardQuests`](QuestEngineBoardQuests.java) | the real one, straight onto `QuestEngine` |
| [`event/`](event/BoardEvents.java) | the outbound native moments: `BoardRotatedEvent`, `BountyRerolledEvent`, fired through `BoardEvents` |

## The outbound events

Two native `IEvent<Void>` POJOs on the shared engine event bus, dispatched through `BoardEvents` with
the library's standard fire contract: resolve the dispatcher, guard on `hasListener()` so a server
with no listeners pays nothing, dispatch synchronously on the calling thread, and guard the whole
body so a listener blowing up never takes a reroll down with it.

- **`BountyRerolledEvent` fires only after the swap is COMMITTED**, so a listener never sees a reroll
  that was refused, that could produce no alternative, or whose price could not be taken.
- **`BoardRotatedEvent` fires when a turnover is first NOTICED, not on a timer.** A board is a pure
  function of the wall clock, so nothing anywhere is scheduled to roll it - which is exactly why a
  restart costs a board nothing. `BoardEvents.noticeRotation` is called wherever a board is about to
  be looked at and fires ONCE per board per period, however many players look next. The FIRST period
  seen for a board is recorded silently: a server that just started has not rotated anything, and
  announcing one on boot would be a lie every listener would learn to ignore.

## What the engine owns, so no surface can lose it

- **The period lock.** A contract completed inside the current rotation period is spent until the
  board turns over, decided off the quest engine's own completion record rather than any bookkeeping
  of ours.
- **The lapse re-arm.** A contract completed in a PAST period is put back within reach the next time
  the board is looked at, which is what makes a rotation genuinely rotate with no timer anywhere. A
  contract being carried is left alone, so nothing in progress is ever discarded.
- **The accept SITE.** Accepting threads the BOARD id, so the quest engine's own completion
  predicate binds the hand-in to that board. Any placed board of that id answers for it; no bounty
  author writes a word of it.
- **The pre-charge reroll probe.** Whether a reroll can produce anything different is settled BEFORE
  a price is drained, so nobody is charged for a guaranteed no-op.

## Rules to keep

- **Every gate is the shared one.** Board access and per-grade accept gates are ordinary
  `progress.gate.GateSpec` blocks answered by the same `GateEvaluator` a quest accept uses, and a
  refusal is passed through in its words. There is no combat level, no power and no level in this
  schema: a board that gates its hard contracts writes a factor condition like anything else.
- **Accept-time only.** A grade gate is checked when a contract is TAKEN, never in the draw, so a
  locked contract is still shown, still legible, and still something to go and earn.
- **`BountyRef` answers per board, and never hands over a membership LIST.** A bounty may hang on
  several boards at different grades; that list is one authored fact, and keeping it in the
  authoring layer is what stops a second membership type existing in the engine.
- **The reroll order is: probe, drain, commit, refund on a lost race.** The commit is cap-checked
  atomically, so a false answer after a successful drain compensates rather than silently keeping
  the price.
- **An unwired price authority REFUSES a paid reroll rather than giving it away.** A board on a
  server that wired no economy stops offering rerolls; it does not start offering free ones.
- **The engine holds no clock.** Every entry point takes `nowMs`.
- **`BoardQuests` stays narrow.** It exists so the board's own decisions are exercisable by handing
  them numbers, not as a general quest facade. A method belongs on it only when the board engine
  genuinely drives it; everything else a surface wants from a quest it asks the quest engine for.
- **A board and a pool are handed IN, per call, and where they come from is not this package's
  business.** The authoring layer may not import an engine type, so
  [`commerce/fold/`](../commerce/fold/CLAUDE.md) is what answers `BoardSpec` and `BountyRef` off the
  authored files, and its `AssetBoardCatalog` is what a surface asks for both.
