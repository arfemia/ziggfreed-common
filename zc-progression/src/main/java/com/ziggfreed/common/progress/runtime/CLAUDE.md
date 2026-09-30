# progress/runtime/ - registering into the one shared runtime

- A one-slot part (a store, the subject source, a call scope, the factor vocabulary) lets a consumer beat the library default, and a second consumer is refused with a SEVERE naming both: never resolve that silently. Contribution parts all apply: gates AND, hooks and listeners fan out, and text, icon and kill seams answer first non-null.
- A library module registering its own owner name uses `ProgressionRuntime.defaults(owner)`, never `registrar(owner)`: the first rank an owner registers at sticks, and a library layer at consumer rank clashes with real consumers instead of yielding to them.
- An owner that publishes from two folds gives each its own slice (`publishQuests(owner, slice, layer)`), or one fold's reload wipes the other's entries.
- React to a produced moment with a `MomentListener`, never a second ECS system on the same native event, and contribute a `KillAttribution` or `KillQualifier` rather than keeping a kill system of your own.
- Wanting your own persistence is not a reason to take the store slot: hook `ProgressionDefaults.onProgressDirty` and `onProgressFlush` (zc-objectives) and keep the default store.
- A surface gets its subject from `ProgressionRuntime.subjects()` and wraps mutating calls in `questScope()` or `achievementScope()`; a locally built subject drops every write on a server where another mod's store is active.
- Sealed parts (`factors`, `maxTrackedQuests`, `maxPinnedAchievements`, `rewardRetryQueue`) are read once when the engines build, and a late one is refused; `maxActiveQuests` is read live.
- A factor read never builds the runtime: `ProgressionFactors` answers null until `isBuilt()`, so an early gate cannot seal parts before their owners register them.
- An `onBuilt` hook must be idempotent against a later explicit publish.
- Fire a feedback moment off a recorded state transition, never off a re-read: self-heal re-asks every standing answer on connect and on every world entry. A new moment with nothing recorded behind it needs a guard like `achievement/UnlockOccasion`.
- A new mutating engine path owes `markDirty` and almost never `flush`; the quest and achievement routers list the flush points.
- The five `ziggfreedcommon:` readings (`quest_known`, `quest_completed`, `quest_completions`, `achievement_earned`, `achievement_points`) are contributed once at library setup, so every factor vocabulary resolves them. An id nothing knows reads null, never 0 (`quest_known` alone reads a definite 0 once the catalogue is built), and a quest counts only once its reward is collected (`COMPLETED`, never `COMPLETED_UNCLAIMED`).
