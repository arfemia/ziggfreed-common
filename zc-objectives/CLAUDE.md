# CLAUDE.md - zc-objectives

The STANDALONE progression experience: the fallback quest + achievement runtime a bare server gets
when no consumer mod runs one of its own, its persisted per-player progress store, the generic
native-event producers that feed it, and the three in-game surfaces that show it - the two-tab
objective book a player reads on their own, the NPC quest page they read at a character, and the
tracked-quest HUD that follows their pinned quests around the world, repainting off the quest
engine's own native events.

## Build

Part of the thirteen-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-objectives`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core` (`SafeLog`, `Subject`, validation, the `Msg` factory, the factor
  model), `zc-loot` (the shared reward vocabulary both engines pay out through), `zc-progression`
  (the engines themselves, their asset stores, and the registration surface every part is contributed
  through), `zc-presentation` (the
  page base, the shared frames/buttons, the retint engine, the toast engine), `zc-cast` (custom
  interaction-Type registration, for the book item's Use), `zc-entity` (the portable `hytale:`
  factor standard library, so a `STAT_THRESHOLD` objective settles itself), `zc-dialogue` (NPC
  IDENTITY: `npc/NpcNames` for what a character is called and `npc/NpcIdentities` for every id it
  answers to, both read off the placement + identity assets - which is what lets the page at a
  character name it and fold its aliases with no consumer filling a seam), `zc-world` (`world.placed`
  ONLY: the shared placed-block ledger the break and pickup producers consult, so neither credits a
  block or an item the player put down themselves), `zc-instance` (the generic
  `InstanceRoundCompletedEvent`, so a finished minigame round feeds the same engines every other
  moment feeds). One-way in every case; zc-world and zc-instance both sit below this module and
  neither reaches back.
- **Depended on by**: no other library module. This module sits ABOVE both `zc-progression` and
  `zc-presentation` and nothing sits above it - a progression book needs the engines and a page,
  and neither of those two modules may reach the other (`zc-progression` may never import
  presentation, and pushing the engines under presentation would drag them onto every page consumer
  in the library). A module above both adds no reverse edge at all.
- **Reverse-edge trap**: none possible - this is the top of the graph. Adding an edge FROM here to
  anything is fine by construction, which is exactly what the `zc-dialogue` edge rests on: nothing
  depends on this module, so an edge out of it can never close a cycle. The trap that matters is the
  one above (nothing may depend on this module, or the "above both" position that justifies its
  existence stops holding).

## Packages

- [`objectives/`](src/main/java/com/ziggfreed/common/objectives/CLAUDE.md) - `ProgressionDefaults`
  (the default per-player component store plus the six generic producers: block break, mob kill,
  craft, pickup and place block off native ECS events, and a finished instance round off the
  shared bus) and the two-tab objective book page. **There is no second
  runtime here** - these are contributions like any other, registered into
  `progress.runtime.ProgressionRuntime` from this module's `setup()`; a consumer mod running its
  own progression REPLACES the parts it answers for through the same registration surface, so
  double-tracking cannot exist rather than being switched off.
  - `objectives/admin/` - the progression admin page: `SystemSwitch` (one registered server-wide
    progression-system switch: label, live read, optional writer) + `SystemSwitches` (the additive
    registry over `zc-core`'s `RegistryLedger`, with the guarded read that answers UNKNOWN rather
    than OFF on a throw and the guarded write that refuses rather than throws), and
    `ProgressionAdminPage` / `ProgressionAdminPages` / `ProgressionAdminDeps` (audience seam
    DEFAULT DENY; opened only by the direct static `ProgressionAdminPages.open`, deliberately not
    a registered destination).
  - `objectives/book/` - the objective book: the full-screen two-tab progression menu
    (`ObjectiveBookPage` the host + verbs, `BookQuestsTab` the quest log, `BookAchievementsTab`
    the two-panel achievements browser), its consumer seams (`ObjectiveBookDeps`, registered via
    `ObjectiveBookPages.deps`), and the item interaction that opens it.
  - `objectives/producer/` - the six producers plus `ProgressDispatch`, the one route
    from any producer to both engines (it resolves each engine's own subject, the zone, and the
    consumer's registered call scope, and asks every contributed `ProgressionSystemGate` per half,
    so an owner who switched a system off for a player still has it off).
  - `objectives/questlist/` - the NPC quest page (`ZigNpcQuestPage`), its consumer seams
    (`NpcQuestPageDeps`), the pure ordering core (`NpcQuestSections`), and `NpcQuestPages`, the one
    call `ProgressionBootstrap` registers as the default quest-list host. Reward chips read through `zc-loot`'s shared `RewardChips` (the
    `RewardChipSource` seam IS its `Source` shape), so this page, a storefront and a results strip
    all read one reward the same way.
  - `objectives/hud/` - the tracked-quest HUD (`TrackedQuestHud` over `zc-presentation`'s
    `KeyedCustomHud`, attached to every player by `TrackedQuestHuds`, repainting on the SIX quest
    events with a per-tick `RepaintCoalescer` and no tick anywhere; `TrackedQuestHudDeps` is the
    consumer's theme / audience / position / enabled seams), plus `TrackedQuestPanelRenderer`, the
    shared renderer for a tracked-quests side panel a page embeds.
  - `objectives/runtime/` - this module's own registration glue over `zc-progression`'s
    `ProgressionRegistrar`.
  - `objectives/store/` - the persisted per-player progress component + its codec.
  - `objectives/command/` - `/zigprogress`, the admin family over THE runtime (quest / achievement
    / memory groups plus `reload`); see
    [its router](src/main/java/com/ziggfreed/common/objectives/command/CLAUDE.md).
  - `objectives/dialogue/` - `DialogueBootstrap`, this module's fill of the seams `zc-dialogue`
    declares and structurally cannot fill (the `hytale:` factor vocabulary, the persistent memory
    store over the progress component, the quest-reset hook), plus `ActiveObjectiveHeader`.

  - `objectives/flair/` - the flair GRANT surface over zc-entity's `ZigFlairComponent`:
    `FlairUnlocks` (the ONE write path: unlock / revoke / list over `(store, ref, playerRef)`,
    answering an `Outcome`; it fires `ZigFlairChangedEvent` through `FlairEvents` and the authored
    `Flair_Unlocked` moment on a REAL change only), `FlairRewardKind` (the unprefixed `Flair` kind,
    `Flair`/`FlairId` param, a flair already held is a successful no-op, a `zigflair grant` retry
    line when no player is live), `FlairText` + `FlairChipReading` (the `flair.<id>.name` name
    ladder every surface shares, resolved namespace-agnostically, else the id spelled out),
    `ZigFlairCommand` (`/zigflair grant|revoke|list`, engine-derived nodes, zc-core's shared
    target-player walk; `grant` warns on an id no loaded lang file names and grants anyway) and
    `FlairBootstrap`, the `setup()` phase registering all three. It lives here rather than beside
    the component because it is the one module that sees the record (entity), the reward
    vocabulary and chip ladder (loot), the toast engine (presentation) and the command walk (core).
  None of the nine router-less subpackages above has its own router; the parent `objectives/`
  router covers them all (`command/` carries its own).

## Shipped resources

`Common/UI/Custom/Pages/{ZigObjectiveBookPage.ui, ZigQuestLogRow.ui, ZigBookObjectiveRow.ui,
ZigBookTagChip.ui, ZigBookCatTab.ui, ZigBookWideTab.ui, ZigBookRewardRow.ui, ZigAchListRow.ui,
ZigAchChipRow.ui, ZigAchCriterionRow.ui, ZigAchCategoryCard.ui, ZigMilestoneCard.ui,
ZigNpcQuestPage.ui, ZigTrackedQuestRow.ui, ZigProgressionAdminPage.ui}` (the
admin page's rows are zc-presentation's shared `ZigFormToggleRow.ui`, appended, not a template of
this module's own, and the NPC quest page appends zc-presentation's shared `Pages/ZigSelectRow.ui`
list row and `Pages/ZigDetailLine.ui` detail line the commerce pages share; needs `zc-presentation`
at RUNTIME as well as compile time, since a page's `.ui` imports the shared frames by path), and
`Common/UI/Custom/Hud/ZigQuestTracker.ui` with the three native objective-HUD textures copied
beside it (`ObjectivePanelContainer.png`, `ObjectiveTaskIconDefault.png`,
`ObjectiveTaskIconComplete.png`), which a server-shipped document resolves by name next to itself.
`Server/Item/Items/Consumables/Ziggfreed_Objective_Book.json` (the book item, whose Use opens the
page via the `zc-cast` interaction-Type registration). `Server/Languages/<locale>/{items.lang,
ziggfreedcommon.progression.lang}`, 9 locales, plus `ziggfreedcommon.flair.lang` (the
`Flair_Unlocked` toast line) and `ziggfreedcommon.flair.admin.lang` (the `/zigflair` family), 9
locales each, and `Server/ZiggfreedCommon/FeedbackMoments/Flair_Unlocked.json`, the library's
neutral flair-unlock notice a pack or an owner overrides by name (`FlairAdminKeysTest` pins the
moment's key to the shipped line and every spoken admin key to the admin file).

## Conventions

All three surfaces read the ONE runtime, so they show whatever any mod on the server contributed,
never a private copy. A consumer that wants none of them still gets the runtime registration (store +
producers) unless it registers its own equivalents through the same surface; the two pages are the
only genuinely optional pieces (the HUD attaches to every player and hides itself when nothing is
pinned; an owner switches it off through the `enabled` supplier on its deps).

**This module registers the NPC quest page as the quest-list host**
(`ProgressionBootstrap.registerQuestListHost`, called once from the root's `setup()`). The host
interface lives in `zc-dialogue`, which this module already depends on, and its ONE abstract method
is the highlight-carrying shape - so the registration is a bare method reference and nothing else,
pure delegation with no logic:

```java
NpcQuestListHosts.register(NpcQuestPages.OWNER, NpcQuestPages.OWNER, NpcQuestPages::open);
```

`NpcQuestPages.open(npcId, highlightQuestId, store, ref, player)` matches that abstract shape
byte-exactly (the plain overload without a highlight delegates into it), so a routed hand-in keeps
its highlighted row. A consumer wanting a different screen registers its own host; the first host
to take the screen wins.

## Tests

30 files: `ProgressionRuntimeTest`-adjacent registration coverage lives in `zc-progression`, while
this module's own suite covers the parts it contributes - `DefaultPartsHandInTest`,
`DefaultPartsRewardGrantTest` (the registered store + producer parts pulling their weight inside a
real runtime), `ZigProgressComponentTest`, `ProgressBlobTest` (the persisted per-player codec),
`ZigProgressBlobCompatTest` + `ZigProgressBlobFixture`/`ZigProgressBlobFixtureGenerator` (the GOLDEN
PIN on that codec's wire format, since a consumer's database backend stores the component in
exactly that form; the checked-in blob is never regenerated), `ProgressStoreContributionTest` (the
dirty/flush fan-out, what it covers, and the two ways a subject can hand the stores their
component), `ProgressDispatchTest`, `ProgressHandleFacetTest`, `PlacedGuardProducerTest` (a
placed-then-broken block dispatches no BREAK_BLOCK progress and a placed-then-picked-up item no
PICKUP_ITEM, while a fresh one still does - the ledger and the producer decision, no live engine),
`AchievementGroupingTest` (the book's category headers: the label ladder and where an undescribed
or uncategorised run reads), `ObjectiveBookDepsTest` (the book's consumer seams: every default
leaves a working page, a null fill falls to the default, a throwing seam costs its own answer),
plus the NPC quest page's two pure halves - `NpcQuestSectionsTest`
(bucketing, ordering, which quest the detail panel opens on) and `NpcQuestPageDepsTest` (the
defaults, and a consumer seam that throws), and the tracked-quest HUD's four -
`TrackedQuestSnapshotTest` (what one paint shows, over an in-memory engine),
`TrackedQuestHudEventTest` (each of the six events repaints the named player once, the objective
event skipped for an unshown quest, the uuid registry), `RepaintCoalescerTest` (a burst is one
paint) and `TrackedQuestHudDepsTest` (the theme seam and every guarded reader), plus
`SystemSwitchesTest` (the admin switch registry: additive + live registration, order-then-id
ordering, a throwing read answering unknown rather than off, an absent or throwing writer refusing
without a throw). How a reward chip
reads is pinned in `zc-loot`'s `RewardChipsTest`, beside the shared vocabulary it belongs to; the pin
event is pinned in `zc-progression`'s `QuestTrackedEventTest`.
