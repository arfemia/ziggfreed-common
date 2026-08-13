# CLAUDE.md - zc-objectives

The STANDALONE progression experience: the fallback quest + achievement runtime a bare server gets
when no consumer mod runs one of its own, its persisted per-player progress store, the generic
native-event producers that feed it, and the in-game two-tab objective book that shows it.

## Build

Part of the twelve-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-objectives`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core` (`SafeLog`, `Subject`, validation, the `Msg` factory, the factor
  model), `zc-loot` (the shared reward vocabulary both engines pay out through), `zc-progression`
  (the engines themselves, their asset stores, and the consumer-claim seam), `zc-presentation` (the
  page base, the shared frames/buttons, the retint engine, the toast engine), `zc-cast` (custom
  interaction-Type registration, for the book item's Use), `zc-entity` (the portable `hytale:`
  factor standard library, so a `STAT_THRESHOLD` objective settles itself).
- **Depended on by**: no other library module. This module sits ABOVE both `zc-progression` and
  `zc-presentation` and nothing sits above it - a progression book needs the engines and a page,
  and neither of those two modules may reach the other (`zc-progression` may never import
  presentation, and pushing the engines under presentation would drag them onto every page consumer
  in the library). A module above both adds no reverse edge at all.
- **Reverse-edge trap**: none possible - this is the top of the graph. Adding an edge FROM here to
  anything is fine by construction; the trap that matters is the one above (nothing may depend on
  this module, or the "above both" position that justifies its existence stops holding).

## Packages

- [`objectives/`](src/main/java/com/ziggfreed/common/objectives/CLAUDE.md) - `ProgressionDefaults`
  (the default per-player component store plus the four generic native-event producers: block
  break, mob kill, craft, pickup) and the two-tab objective book page. **There is no second
  runtime here** - these are contributions like any other, registered into
  `progress.runtime.ProgressionRuntime` from this module's `setup()`; a consumer mod running its
  own progression REPLACES the parts it answers for through the same registration surface, so
  double-tracking cannot exist rather than being switched off.
  - `objectives/book/` - the objective book's rendering + text-arg model.
  - `objectives/producer/` - the four native-event producers.
  - `objectives/runtime/` - this module's own registration glue over `zc-progression`'s
    `ProgressionRegistrar`.
  - `objectives/store/` - the persisted per-player progress component + its codec.

  None of the four subpackages has its own router; the parent `objectives/` router covers them all.

## Shipped resources

`Common/UI/Custom/Pages/{ZigObjectiveBookPage.ui, ZigObjectiveRow.ui}` (needs `zc-presentation` at
RUNTIME as well as compile time, since a page's `.ui` imports the shared frames by path).
`Server/Item/Items/Consumables/Ziggfreed_Objective_Book.json` (the book item, whose Use opens the
page via the `zc-cast` interaction-Type registration). `Server/Languages/<locale>/{items.lang,
ziggfreedcommon.progression.lang}`, 9 locales.

## Conventions

The book reads the ONE runtime, so it shows whatever any mod on the server contributed, never its
own private copy. A consumer that wants no fallback book at all still gets the runtime registration
(store + producers) unless it registers its own equivalents through the same surface; the book page
itself is the only genuinely optional piece.

## Tests

7 files: `ProgressionRuntimeTest`-adjacent registration coverage lives in `zc-progression`, while
this module's own suite covers the parts it contributes - `DefaultPartsHandInTest`,
`DefaultPartsRewardGrantTest` (the registered store + producer parts pulling their weight inside a
real runtime), `ZigProgressComponentTest`, `ProgressBlobTest` (the persisted per-player codec),
`ProgressDispatchTest`, `ProgressHandleFacetTest`, and `ObjectiveBookTextArgsTest` (the book's
render-text argument model).
