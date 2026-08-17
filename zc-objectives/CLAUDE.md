# CLAUDE.md - zc-objectives

The STANDALONE progression experience: the fallback quest + achievement runtime a bare server gets
when no consumer mod runs one of its own, its persisted per-player progress store, the generic
native-event producers that feed it, and the three in-game surfaces that show it - the two-tab
objective book a player reads on their own, the NPC quest page they read at a character, and the
tracked-quest HUD that follows their pinned quests around the world, repainting off the quest
engine's own native events.

## Build

Part of the twelve-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
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
  block or an item the player put down themselves). One-way in every case; zc-world sits below this
  module and never reaches back.
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
  - `objectives/book/` - the objective book's rendering + text-arg model.
  - `objectives/producer/` - the six producers plus `ProgressDispatch`, the one route
    from any producer to both engines (it resolves each engine's own subject, the zone, and the
    consumer's registered call scope, and asks every contributed `ProgressionSystemGate` per half,
    so an owner who switched a system off for a player still has it off).
  - `objectives/questlist/` - the NPC quest page (`ZigNpcQuestPage`), its consumer seams
    (`NpcQuestPageDeps`), the pure ordering core (`NpcQuestSections`), and `NpcQuestPages`, the one
    call a wiring root makes. Reward chips read through `zc-loot`'s shared `RewardChips` (the
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

  None of the six subpackages has its own router; the parent `objectives/` router covers them all.

## Shipped resources

`Common/UI/Custom/Pages/{ZigObjectiveBookPage.ui, ZigObjectiveRow.ui, ZigNpcQuestPage.ui,
ZigNpcQuestRow.ui, ZigNpcQuestLine.ui, ZigTrackedQuestRow.ui}` (needs `zc-presentation` at RUNTIME
as well as compile time, since a page's `.ui` imports the shared frames by path), and
`Common/UI/Custom/Hud/ZigQuestTracker.ui` with the three native objective-HUD textures copied
beside it (`ObjectivePanelContainer.png`, `ObjectiveTaskIconDefault.png`,
`ObjectiveTaskIconComplete.png`), which a server-shipped document resolves by name next to itself.
`Server/Item/Items/Consumables/Ziggfreed_Objective_Book.json` (the book item, whose Use opens the
page via the `zc-cast` interaction-Type registration). `Server/Languages/<locale>/{items.lang,
ziggfreedcommon.progression.lang}`, 9 locales.

## Conventions

All three surfaces read the ONE runtime, so they show whatever any mod on the server contributed,
never a private copy. A consumer that wants none of them still gets the runtime registration (store +
producers) unless it registers its own equivalents through the same surface; the two pages are the
only genuinely optional pieces (the HUD attaches to every player and hides itself when nothing is
pinned; an owner switches it off through the `enabled` supplier on its deps).

**The wiring root registers the NPC quest page as the quest-list host.** The host interface lives in
`zc-dialogue` and the root is the one place a registration joining two domains belongs, so both
`NpcQuestPages.open` overloads are written to that interface's two shapes byte-exactly and the root
supplies the object - pure delegation, no logic:

```java
NpcQuestListHosts.register(NpcQuestPages.OWNER, NpcQuestPages.OWNER, new NpcQuestListHost() {
    @Override public boolean open(String npcId, Store<EntityStore> store, Ref<EntityStore> ref,
            Player player) {
        return NpcQuestPages.open(npcId, store, ref, player);
    }
    @Override public boolean open(String npcId, String highlightQuestId, Store<EntityStore> store,
            Ref<EntityStore> ref, Player player) {
        return NpcQuestPages.open(npcId, highlightQuestId, store, ref, player);
    }
});
```

**A bare `NpcQuestPages::open` method reference COMPILES here and silently loses every highlight**,
which is the one mistake to know about. The host's highlighting method is a DEFAULT that delegates to
the plain one, so a lambda or method reference - which can only ever implement the single abstract
method - inherits that default and routes a hand-in to an unhighlighted list. The object form above
overrides both. (The interface could remove the hazard by making the highlighting method the abstract
one and the plain one the default, which would make a single method reference correct; that is
`zc-dialogue`'s call.) A consumer wanting a different screen registers its own host; the first host to
take the screen wins.

## Tests

18 files: `ProgressionRuntimeTest`-adjacent registration coverage lives in `zc-progression`, while
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
or uncategorised run reads), plus the NPC quest page's two pure halves - `NpcQuestSectionsTest`
(bucketing, ordering, which quest the detail panel opens on) and `NpcQuestPageDepsTest` (the
defaults, and a consumer seam that throws), and the tracked-quest HUD's four -
`TrackedQuestSnapshotTest` (what one paint shows, over an in-memory engine),
`TrackedQuestHudEventTest` (each of the six events repaints the named player once, the objective
event skipped for an unshown quest, the uuid registry), `RepaintCoalescerTest` (a burst is one
paint) and `TrackedQuestHudDepsTest` (the theme seam and every guarded reader). How a reward chip
reads is pinned in `zc-loot`'s `RewardChipsTest`, beside the shared vocabulary it belongs to; the pin
event is pinned in `zc-progression`'s `QuestTrackedEventTest`.
