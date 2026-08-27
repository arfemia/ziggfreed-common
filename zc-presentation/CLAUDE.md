# CLAUDE.md - zc-presentation

Everything a player sees or hears: UI primitives (retint, theme, toast, HUD, settings forms, ledger
rows), 3D sound, camera effects, and the notification wrappers. This is the module every page-ship
consumer needs at runtime even without a compile edge, because a page's `.ui` file imports
`ZigButtons.ui`/`ZigFrames.ui` from here by path and Gradle never looks inside a `.ui` file.

## Build

Part of the twelve-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-presentation`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core` only.
- **Depended on by (compile edge)**: `zc-dialogue`, `zc-instance`, `zc-objectives`.
- **Depended on by (runtime `.ui` reference only, no compile edge)**: every module that ships a
  page - see the root module table's "Ships `.ui`" column. A module can need this one at runtime
  and Gradle will never say so; the day anything ships as a separate jar, that column is the audit
  trail.
- **Reverse-edge trap**: none declared today (this module sits low enough that nothing above it
  would need to be avoided), but keep it that way - a `ui/` primitive that starts importing
  `dialogue`/`instance`/`progress` content is domain vocabulary leaking into presentation, which
  belongs the other way around.

## Packages

- [`camera/`](src/main/java/com/ziggfreed/common/camera/CLAUDE.md) - `CameraShakeService` +
  `ServerCameraService`.
- `feedback/` - `Notify` (Default/Danger/Warning/Success toasts, the item-keyed stacking form, and
  `withIcon` for a two-line toast ILLUSTRATED by an item with no quantity badge and no client-side
  stacking), `EventTitles` (centered banner), `PickupMimic` (native-pickup-mimic notifier for a
  programmatic item grant that never went through a real ground pickup), `ObjectiveHud`. No router
  of its own; see the root router's `feedback/` bullet for the full primitive list.
  - `feedback/moment/` - the authored-feedback engine: `FeedbackMomentAsset` (Pattern A, the file
    name IS the moment id, at `Server/ZiggfreedCommon/FeedbackMoments/`, with four independent
    groups - `Toast` / `Broadcast` / `Sound` / `Command` - over one reused `Line` leaf of
    `{Key, KeyArg, Args, Color}`, plus `Variants`: an ordered list of `{When, <groups>}` entries
    where the first whose `When` values all match the moment's arguments overlays only the groups
    it restates, so ONE file says "your bags are full" and "collect it where you took it" for two
    cases of the same moment), `FeedbackMomentConfig` (the `defaults < pack < owner` fold) and
    `FeedbackEngine.fire(momentId, Subject, args)`. It knows nothing about what PRODUCED a moment,
    which is what lets a quest engine, a shop and a mod that does not exist yet share one authoring
    surface; joining the two ends is the wiring root's job. A moment nobody authored a file for does
    nothing, a line naming a value the moment did not carry is skipped, and a part that throws costs
    its own part. A `Key` is authored WITHOUT a namespace and resolved through `i18n/ContentKeys`,
    exactly like every other authored key in this library (a full registered id passes through
    untouched, which is how the library's own defaults name their lang file); a `KeyArg` reads the
    key from one of the moment's own values instead, falling back to `Key`, so a per-content wording
    (an achievement's own announcement) needs no file per achievement. The name `player` always
    answers with the subject's name. The toast's PICTURE is not a leaf: it is read from the one
    fixed argument name `icon`, so a producer with a picture to offer supplies one and every
    authored toast gets it with nothing written for it. `Toast.EveryPercent` keeps a progress moment
    from chattering: an ordinary tick shows only when it crosses a multiple of that many percent
    (the finish always shows). `FeedbackEngine.answers(momentId)` is the cheap "is there a file for
    this at all" question a producer asks before composing what an expensive moment would carry,
    and the wiring root pairs it with the reaction through `ProgressionFeedbackHook.of`.
    `FeedbackAudience` is the one thing a static file cannot answer: the SUBJECT's own handle says
    whether this player wants the personal notification for this moment, told the moment's values
    plus `milestone` (whether a progress tick crossed the authored mark) so a consumer's own
    "every tick / milestones / finishes / nothing" setting is answered from them (a handle with no
    opinion gets what was authored), and only the toast is gated that way - a banner, a sound and a
    command are not one player's screen. **This module SHIPS the library's neutral default file for
    each of the seven moments the progression engines announce** (`Quest_Completed`, `Quest_Parked`,
    `Quest_Claimed`, `Quest_Objective_Progressed`, `Achievement_Unlocked`, `Achievement_Claimed`,
    `Achievement_Server_First_Lost`) plus their wording in `ziggfreedcommon.feedback.lang` (nine
    locales); a consumer's same-id file wins by pack order (`FeedbackMomentOverrideOrderTest` pins
    it through the engine map). No router of its own; see the asset's javadoc, which is the
    authoring reference.
- [`sound/`](src/main/java/com/ziggfreed/common/sound/CLAUDE.md) - `Sound3D`.
- `ui/` - `CustomHudHelper`, `ZigRichButton` (the clickable-rich-text primitive every labeled
  button in the library uses), `UiRetint` (the generic palette-to-selector retint primitive),
  `SettingsUiUtil` (settings-form binding helper), `StatusTones` (the six-tone status-colour
  vocabulary progression and commerce surfaces paint state with: ready / available / in progress /
  soft block / limited / locked), `TagColors` (the deterministic keyword + hash colour table for
  free-string tag chips, so one tag reads one colour everywhere). No router at this top level
  (mixed single-file primitives); the four structured subpackages below each have their own:
  - [`ui/form/`](src/main/java/com/ziggfreed/common/ui/form/CLAUDE.md) - `FieldSpec` +
    `SettingsForm`, the generic settings-form engine.
  - [`ui/hud/`](src/main/java/com/ziggfreed/common/ui/hud/CLAUDE.md) - `KeyedCustomHud` +
    `HudPosition`.
  - `ui/route/` - the DESTINATION vocabulary: `Destination` (a `Type`-discriminated union authored
    as `{"Type": "...", ...}` or as one bare word for a type with no fields) + `Destinations` (the
    open registry a mod claims a type in, over `registry/RegistryLedger`) + `DestinationType`
    (typeId + class + codec + `DestinationHandler` + optional `DestinationCheck`) +
    `DestinationContext` (the player's live handles plus the nullable npc / placement / deps-key
    leaves). ONE value answers "what does this open" for a placement's press-F, a dialogue option
    and a page button alike, so no compound string is ever parsed. **Register in your plugin's
    `setup()`, before assets load**, and an unknown `Type` FAILS THE READ naming the file - a
    destination nothing can open must never be a button that silently does nothing. It sits here
    rather than in a domain module because routing is presentation: the vocabulary holds no screen
    of its own, and the modules that own screens (zc-dialogue seeds `Dialogue` and `Quests`)
    register into it. No router of its own; see the class javadoc.
  - [`ui/rows/`](src/main/java/com/ziggfreed/common/ui/rows/CLAUDE.md) - `SummaryRow` +
    `SummaryRowRenderer`, the fixed-slot ledger row.
  - [`ui/toast/`](src/main/java/com/ziggfreed/common/ui/toast/CLAUDE.md) - the lifted
    transport-agnostic toast engine (`ToastController`/`ToastRenderer`/`ToastSpec`/`ToastLine`/
    `ToastKind` + `ToastablePage<T>`).
  - `ui/theme/` - `Palette` + `ThemeRecord`, the mod-agnostic theme value model that pairs with
    `UiRetint`. No router of its own; see the root router's `ui/` bullet.

## Shipped resources

`Common/UI/Custom/Common/{ZigButtons.ui, ZigFrames.ui}` (the shared neutral button/frame styles
every page in the library imports), `Common/UI/Custom/Pages/{ZigFormDropdownRow.ui,
ZigFormFieldRow.ui, ZigFormHeaderRow.ui, ZigFormNoteRow.ui, ZigFormToggleRow.ui, ZigListRow.ui,
ZigSelectRow.ui, ZigDetailLine.ui, ZigToast.ui}` plus `ZigToastFrame.png` (`ZigSelectRow.ui` is the
one selectable list row - content row and section heading in a single template, `#RowBtn` + hidden
`#SectionLabel`/`#RowBadge`/`#SectionMeta` - and `ZigDetailLine.ui` the one detail-panel line, both
appended by the NPC quest page and both commerce pages so a readability step lands everywhere at
once). Under `Server/`: the seven neutral default feedback moments
at `Server/ZiggfreedCommon/FeedbackMoments/<moment id>.json` and their wording at
`Server/Languages/<locale>/ziggfreedcommon.feedback.lang` (nine locales) - the library's own default
CONTENT a consumer overrides by id, every file carrying a public-facing `$Comment` naming the
arguments the moment carries and how to override it.

## Conventions

Every labeled clickable button is a `ZigRichButton` (a `Button` + inner `#Label` set via
`.TextSpans`), never a `TextButton` whose `.Text` is set from Java - see the root router's hard
rule under Conventions for why. Colour/bold/param substitution only render on `.TextSpans`, never
`.Text`. i18n is parameterized OUT of every primitive here (pre-built `Message`s only); no Java in
this module owns a namespace prefix - the shipped default moment files name their own lang ids in
full (`ziggfreedcommon.feedback.<key>`), which the moment engine passes through untouched.

## Tests

Thin relative to the package count: `HudPositionTest` (corner-preset parsing + anchor math),
`SettingsFormTest` (field-spec render/refresh/collect round trip), and `DestinationsTest` (the
routing vocabulary's decode + dispatch + audit contract: a registered type's own fields, the
bare-string form as the same value, an unknown or mis-cased `Type` failing the read, a late
registration still taking effect, a throwing handler counted against its owner). The toast engine,
retint engine,
and rich-button primitive have no unit coverage here; they are validated in-game per the general
`.ui` rule (`.ui` files are not compiled, validate in-game). `FeedbackEngineTest` covers the moment
schema's decode and inheritance, variant selection, `KeyArg`, the `EveryPercent` mark and the
audience question, plus everything a broken authoring file or an absent player could turn into a
throw; `ShippedFeedbackMomentsTest` decodes every shipped default and checks each line's key against
the shipped en-US lang file; `FeedbackMomentOverrideOrderTest` pins that a consumer's same-id file
replaces the library's through the engine map's own pack chain. The drawing itself is packets and is
validated in game.
