# CLAUDE.md - `achievement/` (module `zc-progression`)

The ALWAYS-ON lifecycle engine, and the PEER of [`../quest/`](../quest/CLAUDE.md) over the shared
[`../progress/`](../progress/CLAUDE.md) cores. Nothing is accepted, nothing is abandoned, nothing
comes back on a cooldown: every criterion of every catalogued achievement listens from the first
event, and an achievement earns itself the moment its criteria are all met.

Package root `com.ziggfreed.common.achievement`. Module edge: zc-core, plus zc-loot for the reward
vocabulary. No engine types outside the native events.

## Where THE runtime lives

THE shared instance comes from [`../progress/runtime/`](../progress/runtime/CLAUDE.md), not from a
field somebody holds. `builder()` is for tests and for a private engine. **Milestones are published
through `setMilestones`, never by rebuilding the engine** - a rebuild orphans every cached reference,
which one shared instance cannot afford, and it is exactly why that method exists beside
`setAchievements`.

## The pieces

| Class | What it is |
|---|---|
| `Achievement` (+ `.Builder`) | the resolved definition: ORDERED criteria, meta children, two reward lists, points, and four independent switches |
| `AchievementStatus` | LOCKED / UNLOCKED / CLAIMED - three states, because "in progress" is a count, not a status |
| `AchievementEngine` (+ `.Builder`) | the runtime: dispatch, earn, collect, revoke, points, milestones, pins, self-heal |
| `AchievementProgressStore` | THE persistence seam. Composite `"<id>#<index>"` keys, the legacy fallback, and the reserved-character check all live here as DEFAULTS |
| `InMemoryAchievementProgressStore` | the complete store that dies with the process |
| `AchievementGates` | the consumer's say: `canProgress` / `canUnlock` / `canReceiveRewards` / `visible`, all default-yes |
| `AchievementEngine.Builder#factors` / `#factorContext` | the OPTIONAL factor pair, the same two knobs the quest engine takes; unwired, `STAT_THRESHOLD` is purely consumer-fired |
| `AchievementMilestone` | a reward for a points TOTAL rather than for any one achievement |
| `event/` | `AchievementEvents` + the three native `IEvent<Void>` POJOs (progressed / unlocked / claimed) |
| `asset/` | the authoring layer - see [`asset/CLAUDE.md`](asset/CLAUDE.md) |

## Rules to keep

- **The criteria order is PERMANENT.** Progress is stored per criterion by its POSITION
  (`"<id>#<index>"`), so appending is safe while inserting, removing, or reordering moves every
  player's progress onto a different criterion. `AchievementEngineTest` asserts the hazard directly:
  anyone who wants reordering to be safe has to come and change that test on purpose.
- **The composite key's legacy fallback is ONE-WAY.** A read of criterion 0 with nothing under the
  composite key falls back to the bare achievement id (where a store predating per-criterion keys
  wrote its single number); the first WRITE at index 0 clears that bare key. Without the clear, a
  reset criterion would resurrect a pre-migration value.
- **Two reward lists, two moments.** `autoRewards` land on earning; `claimRewards` wait. An
  achievement with no claim rewards settles in ONE step, which is what makes CLAIMED reachable with
  no second interaction. Never collapse them into one list plus a flag.
- **Never a mode.** `available` / `hidden` / `countsTowardTotal` are three independent switches; a
  retired one-off is hidden-or-not, counting-or-not, earnable-or-not in any combination. A new
  achievement "type" constant is the smell this exists to prevent.
- **A standing-value criterion is re-read at SELF-HEAL and nowhere else**, which is the one place
  this engine deliberately does LESS than its peer. `STAT_THRESHOLD` (see
  [`../progress/`](../progress/CLAUDE.md) for the kind's contract) names a state nothing ever fires
  for, so `refreshStatThresholds` goes and reads it. The quest engine also piggybacks on a dispatch
  that moved the same quest, and that is cheap because it is bounded by the handful of quests one
  player is CARRYING. Nothing is accepted here, so the same piggyback would re-read part of the
  WHOLE catalogue on every progressing event of every player, forever, to catch a value that is
  still going to be there at the next self-heal - and self-heal already runs on login and whenever
  an achievement surface opens, which is every moment the answer is about to be looked at. A
  consumer wanting it sooner dispatches the kind itself, exactly as it would any other.
- **A gate that throws is a REFUSAL**, reported once per gate per achievement. A broken gate must
  never open one. `canProgress` also guards the threshold re-read, so a refusal there writes nothing
  rather than quietly bypassing the gate through a path no producer fired.
- **A refusal loses nothing.** `canUnlock` refusing leaves the criteria MET, so `selfHeal` earns it
  the moment the answer changes. That is what makes a race arbitrable without a rollback.
- **The engine never names a consumer's world.** No feature flags, no class ids, no progression
  vocabulary. Everything of that shape is a question asked of `AchievementGates`. The module
  agnosticism test scans this package.
- **Milestones are STATE, not moments.** They are recomputed whenever a total changes and a consumer
  renders their status. The moment worth reacting to is the achievement whose earning crossed the
  threshold, and that already fires.

## Wiring one up

```java
AchievementEngine engine = AchievementEngine.builder()
        .objectiveKinds(myObjectiveKinds)
        .rewardKinds(myRewardKinds)
        .store(myStore)
        .gates(myGates)
        .milestone(AchievementMilestone.claimable(100, List.of(...)))
        .build();
engine.setAchievements(AchievementAssetStore.getInstance().resolveAll().achievements());
```
Then feed it: `engine.dispatch(subject, kind, target, qualifier, amount)` from every producer, and
`engine.selfHeal(subject)` when a player becomes ready.
