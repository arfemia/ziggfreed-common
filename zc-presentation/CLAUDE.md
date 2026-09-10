# CLAUDE.md - zc-presentation

Everything a player sees or hears: UI primitives (retint, theme, toast, HUD, settings forms, ledger
rows), 3D sound, camera effects, and the notification wrappers. This is the module every page-ship
consumer needs at runtime even without a compile edge, because a page's `.ui` file imports
`ZigButtons.ui`/`ZigFrames.ui` from here by path and Gradle never looks inside a `.ui` file.

## Build

Part of the fourteen-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-presentation`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core`, plus `zc-loot` for ONE seam - `ui/toast/RewardToastLines`, the bridge
  from the reward-chip reading to toast body rows. That edge points DOWN the graph (zc-loot reaches
  only zc-core), so it can never cycle.
- **Depended on by (compile edge)**: `zc-commerce`, `zc-dialogue`, `zc-encounter` (the boss framework's payout fires its beats through the authored-feedback engine), `zc-instance`, `zc-objectives`.
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
- `feedback/` - `Notify` (Default/Danger/Warning/Success toasts, the item-keyed gain form, and
  `withIcon` for a two-line toast ILLUSTRATED by an item with no quantity badge, plus an
  explicit-`NotificationStyle` overload for a notice carrying its own tone). **Every entry point
  has a `tag` form, and a tag is the ONLY thing that stops a repeating notice piling up**: the
  native `Notification` packet carries a `Tag` the client merges on, so a later notice under the
  same tag REPLACES the showing one in place (an item notice naming the same item ADDS to its
  quantity badge instead) while an untagged notice is always one more entry in the feed. A merged
  item entry keeps the FIRST send's words and only its badge climbs, so one tag covers one wording
  (a lucky find and an ordinary one are two tags) and the changing number rides the badge, never
  the title; two notices that must both be READ never share a tag. This is the first-party idiom,
  not a trick: `BuilderToolsPlugin` tags its own per-stroke progress notices exactly this way.
  Also here: `EventTitles` (centered banner), `PickupMimic` (native-pickup-mimic notifier for a
  programmatic item grant that never went through a real ground pickup), `ObjectiveHud`. No router
  of its own; see the root router's `feedback/` bullet for the full primitive list.
  - `feedback/moment/` - the authored-feedback engine: `FeedbackMomentAsset` (Pattern A, the file
    name IS the moment id, at `Server/ZiggfreedCommon/FeedbackMoments/`, with four independent
    groups - `Toast` / `Broadcast` / `Sound` / `Command` - over one reused `Line` leaf of
    `{Key, KeyArg, Args, Color}` (`Toast` adds `Tone` and a `Rows` group of its own; `Broadcast` adds
    `Major` plus four independent SCOPING knobs deciding who sees a banner and how often,
    `ToParticipants` / `SameWorldOnly` / `RadiusBlocks` / `MinSecondsBetween`, answered by
    `BroadcastScope` off the two fixed argument names `participants` and `source` a producer
    supplies, so a moment fired once per participant announces to the world once), plus `Variants`: an ordered list of `{When, <groups>}` entries
    where the first whose `When` values all match the moment's arguments overlays only the groups
    it restates, so ONE file says "your bags are full" and "collect it where you took it" for two
    cases of the same moment), `FeedbackMomentConfig` (the `defaults < pack < owner` fold) and
    `FeedbackEngine.fire(momentId, Subject, args)`. It knows nothing about what PRODUCED a moment,
    which is what lets a quest engine, a shop and a mod that does not exist yet share one authoring
    surface; joining the two ends belongs to a layer that sees both (`zc-objectives`'
    `ProgressionBootstrap.registerFeedbackMoments` does it for the progression engines). A moment nobody authored a file for does
    nothing, a line naming a value the moment did not carry is skipped, and a part that throws costs
    its own part. A `Key` is authored WITHOUT a namespace and resolved through `i18n/ContentKeys`,
    exactly like every other authored key in this library (a full registered id passes through
    untouched, which is how the library's own defaults name their lang file); a `KeyArg` reads the
    key from one of the moment's own values instead, falling back to `Key`, so a per-content wording
    (an achievement's own announcement) needs no file per achievement. The name `player` always
    answers with the subject's name. The toast's PICTURE is not a leaf: it is read from the one
    fixed argument name `icon`, so a producer with a picture to offer supplies one and every
    authored toast gets it with nothing written for it. The toast's reward ROWS follow the same
    doctrine: the fixed argument name `rewards` (a `List<RewardSpec>`; both progression engines
    supply it, deferred, on their completed / parked / claimed / unlocked moments) paints one row
    per readable reward under the headline of the IN-PAGE toast through `ui/toast/RewardToastLines`
    (source null, so the contributed kind readings still answer), tuned by the authored
    `Toast.Rows` group - `Show`, and `Max` before the shared "+N more" overflow line
    (`rewards.more` in the shipped lang); the corner feed outside a menu has no rows.
    `Toast.Tone` is the closed `ToastKind` word (Info / Success / Reward / Warning / Error, an
    Asset Editor dropdown via `EditorSchema`; unauthored reads Info) that the in-page frame, the
    headline colour AND the feed's native style all take together, so the frame and the words can
    never disagree; the in-page toast is built `silent()` because the moment's own `Sound` group
    is the one audio authority. `Toast.EveryPercent` keeps a progress moment
    from chattering: an ordinary tick shows only when it crosses a multiple of that many percent
    (the finish always shows). `Toast.Merge` is the other half of that, for the CORNER FEED: a
    merging moment's notice goes out under a tag of `<momentId>|<source>`
    (`FeedbackEngine.feedTag`), so repeats about the same thing rewrite ONE line instead of stacking
    a column of counters over whatever else the player needed to see, and two sources keep two
    lines. Default false, because a moment the player has to actually READ must not be replaced
    before they get to it; a `Variant` restates the WHOLE `Toast` group, so a variant that must
    merge (the finishing tick, landing on the counter it finishes) authors the leaf itself. `FeedbackEngine.answers(momentId)` is the cheap "is there a file for
    this at all" question a producer asks before composing what an expensive moment would carry,
    and `ProgressionBootstrap` pairs it with the reaction through `ProgressionFeedbackHook.of`.
    `FeedbackSurfaces` is the third branch of the toast's "where is the player looking"
    decision, beside the open-page one: a surface that ALREADY spells a moment out registers a
    `Reader` from its own layer, and a moment it claims draws nothing in the corner feed (the
    library registers the tracked-quest HUD's, so a step counting up on a pinned quest does not also
    stack a notice per tick over everything else). Asked only on the feed branch, after the page
    check - a menu covers such a panel, so a moment drawn into the menu repeats nothing - and only
    the feed notice is dropped: the sound, the banner and the command are untouched. Readers are
    additive, asked until one says yes, and one that throws costs its own answer only.
    `FeedbackAudience` is the one thing a static file cannot answer: the SUBJECT's own handle says
    whether this player wants the personal notification for this moment, told the moment's values
    plus `milestone` (whether a progress tick crossed the authored mark) so a consumer's own
    "every tick / milestones / finishes / nothing" setting is answered from them (a handle with no
    opinion gets what was authored), and only the toast is gated that way - a banner, a sound and a
    command are not one player's screen. **This module SHIPS the library's neutral default file
    for each of the ELEVEN moments the library's own engines announce**: the seven the
    progression engines fire (`Quest_Completed`, `Quest_Parked`, `Quest_Claimed`,
    `Quest_Objective_Progressed`, `Achievement_Unlocked`, `Achievement_Claimed`,
    `Achievement_Server_First_Lost`), each authoring its `Tone` (payouts Reward, the lost race
    Warning, the progress tick Info with a Success finish, the full-bag park Error) and no
    per-line `Color`, plus the four the boss framework announces (`Encounter_Engaged`,
    `Encounter_Phase_Changed`, `Encounter_Defeated`, `Encounter_Wiped`, the ids
    `EncounterBindingAsset`'s four feedback leaves read as when unauthored), where the engaged
    notice is a scoped `Broadcast` rather than a toast; the wording for all eleven lives in
    `ziggfreedcommon.feedback.lang` (nine locales); a consumer's same-id file wins by pack order
    (`FeedbackMomentOverrideOrderTest` pins it through the engine map). No router of its own; see
    the asset's javadoc, which is the authoring reference.
- [`sound/`](src/main/java/com/ziggfreed/common/sound/CLAUDE.md) - `Sound3D`.
- `ui/` - `CustomHudHelper`, `ZigRichButton` (the clickable-rich-text primitive every labeled
  button in the library uses), `ZigSearchRow` (the Java half of the shared search row
  `Common/ZigSearchRow.ui`: `wire` seeds the field, labels Search and Clear from this module's
  own `ziggfreedcommon.ui.lang`, binds both clicks and shows Clear only while there is text;
  `carry` puts the row's live value on any OTHER binding under an `@`-prefixed key, refusing a
  bare one, because the `@` is the client's directive to resolve the value as an element path
  and without it the path string ships literally and lands in the field as typed text;
  `valuePath` is the scoped `"<row> #SearchField.Value"`. A search field binds NOTHING per
  keystroke: a rebuild on every character steals focus, so Search submits and the live text
  rides the page's other bindings, the objective book's shape), `UiRetint` (the generic
  palette-to-selector retint primitive), `SettingsUiUtil` (settings-form binding helper; its
  `directive(key)` is the one `@`-check every `.Value`-mapping helper in the family routes
  through), `UiText` (the ONE way a page writes a `.Text`
  property, pinned by `TextSinkGoesThroughUiTextTest`: a translation is sent for the client to
  resolve, anything else is flattened through `flatten`, because a non-`MessageId` `Message`
  document in that slot DISCONNECTS the client - so a String-only sink resolves its translation
  through `flatten`), `StatusTones` (the six-tone status-colour
  vocabulary progression and commerce surfaces paint state with: ready / available / in progress /
  soft block / limited / locked), `TagColors` (the deterministic keyword + hash colour table for
  free-string tag chips, so one tag reads one colour everywhere). No router at this top level
  (mixed single-file primitives); the four structured subpackages below each have their own:
  - [`ui/form/`](src/main/java/com/ziggfreed/common/ui/form/CLAUDE.md) - `FieldSpec` +
    `SettingsForm`, the generic settings-form engine.
  - [`ui/hud/`](src/main/java/com/ziggfreed/common/ui/hud/CLAUDE.md) - `KeyedCustomHud` +
    `HudPosition` + `RepaintCoalescer`.
  - [`ui/hud/card/`](src/main/java/com/ziggfreed/common/ui/hud/card/CLAUDE.md) - the ONE look
    every HUD card is drawn in: the `HudCards` store (`HudCardAsset` / `HudCardConfig` /
    `HudCardOwnerLayers`, one `Color` hex with an alpha multiplied over the card's frame, the
    identity pushing nothing) and `HudCardLook`, the pure arithmetic every card and the bar
    dressing share.
  - [`ui/hud/bar/`](src/main/java/com/ziggfreed/common/ui/hud/bar/CLAUDE.md) - the shared
    progress-bar HUD: rows created on demand by `HudBars.moved` (a value, with the `HudBarReading`
    and the `HudBarDisplay` the consumer hands over with the move; no source seam and no
    registry) and `HudBars.itemMoved` (an item, dressed by itself), `HudBarLook` the settled look, the
    `HudBars` store as an OPTIONAL per-row override and the `HudBarPanels` store for placement,
    and `HudBarHud` the panel that draws it (fill rows above item rows).
  - `ui/icon/` - `IconRenderer`, the ONE seam that paints an `icon.IconSpec` into a row or a chip:
    a row ships a one-slot `ItemGrid #IcoItem` (styled from the `ZigButtons.ui` ladder; the item
    lands on its `.Slots` as an `ItemGridSlot`, since the client has no `ItemIcon` widget type) and
    an `AssetImage #IcoTex` side by side and this toggles the right one by `.Visible`, the item id
    winning when both are set, so no surface re-decides item-versus-texture and a row with nothing
    to draw keeps both hidden; an item id nothing answers to falls through to the texture.
    `applyItemSlot` takes a slot the caller built instead of an id, for a picture that carries its
    own hover name (a reward line's), and hides the texture the same way. The shipped rows
    carrying the pair are `Pages/ZigDetailLine.ui` and `Pages/ZigListRow.ui` here (the latter's
    sits in a hidden `#IconSlot` a consumer shows off the seam's return value) plus the objective
    book's criterion row. No router of its own; see the class javadoc.
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

`Common/UI/Custom/Common/{ZigButtons.ui, ZigFrames.ui, ZigSearchRow.ui}` (the shared neutral
button/frame styles every page in the library imports, and the parameterised search-row component
`@ZigSearchRow` - field + Search + Clear under an instance id, so one page can hold two - that
every search field in the family instantiates, wired from Java by `ui/ZigSearchRow`),
`Common/UI/Custom/Pages/{ZigFormDropdownRow.ui,
ZigFormFieldRow.ui, ZigFormHeaderRow.ui, ZigFormNoteRow.ui, ZigFormToggleRow.ui, ZigListRow.ui,
ZigSelectRow.ui, ZigDetailLine.ui, ZigToast.ui}` plus `ZigToastFrame.png` (`ZigSelectRow.ui` is the
one selectable list row - content row and section heading in a single template, `#RowBtn` + hidden
`#SectionLabel`/`#RowBadge`/`#SectionMeta` - appended by the NPC quest page and both commerce
pages; `ZigDetailLine.ui` is the ONE objective / reward / refusal line of the whole family - row
30, icon 28 on `@ZigIconGrid28`, font 16, the size set in that file and nowhere else - appended by
the NPC quest page, both commerce pages, the objective book's two tabs and a consumer mod's own
reward and preview rows alike, so a readability step lands everywhere at once; and the section
label above any such list is `ZigButtons.ui`'s `@ZigSectionHeaderStyle`, the one header
`LabelStyle` every page references rather than spelling its own, spread with a `TextColor`
override where a header must keep its own colour). Under `Server/`: the eleven neutral default
feedback moments (the seven the progression
engines announce plus the four the boss framework announces)
at `Server/ZiggfreedCommon/FeedbackMoments/<moment id>.json` and their wording at
`Server/Languages/<locale>/ziggfreedcommon.feedback.lang` (nine locales) - the library's own default
CONTENT a consumer overrides by id, every file carrying a public-facing `$Comment` naming the
arguments the moment carries and how to override it. `Server/Languages/<locale>/ziggfreedcommon.ui.lang`
(nine locales) carries the words the shared widgets put on screen: the search row's Search and
Clear, and the progress-bar panel's gain (`hud.bar.gain`, `+{0, number}`, which an item row's running
count rides too). `Common/UI/Custom/Hud/ZigHudBars.ui`
is the progress-bar panel's document, and `Server/ZiggfreedCommon/HudBarPanels/Default.json` the
panel every row is drawn on (on, TopLeft (16, 216), four rows), a consumer or an owner overriding
it by id; the library ships no `HudBars` file, since a row needs none.
`Server/ZiggfreedCommon/HudCards/Default.json` is the one look every HUD card wears, shipped at the
identity `#ffffffff` (owner layer `mods/ziggfreedcommon/hud-cards.json`).

## Conventions

Every labeled clickable button is a `ZigRichButton` (a `Button` + inner `#Label` set via
`.TextSpans`), never a `TextButton` whose `.Text` is set from Java - see the root router's hard
rule under Conventions for why. Colour/bold/param substitution only render on `.TextSpans`, never
`.Text`. i18n is parameterized OUT of every primitive that paints a consumer's words (pre-built
`Message`s only); the Java here owns exactly two lang files of its own - the shipped default moment
files name their ids in full (`ziggfreedcommon.feedback.<key>`), which the moment engine passes
through untouched, and the shared search row's two button words resolve from
`ziggfreedcommon.ui.lang`, since a widget the library ships whole labels itself.

A binding that carries a LIVE element value names it under an `@`-prefixed key (`"@SearchInput"`,
`"@DropdownValue"`) and the receiving codec declares that same `@`-key: the `@` is the client's
directive to read the value as an element path. Every helper that maps a `.Value` path routes its
key through `SettingsUiUtil.directive`, which refuses a bare key. A SEARCH field is never bound
per keystroke; it is the shared `ZigSearchRow`.

## Tests

Thin relative to the package count: `HudPositionTest` (corner-preset parsing + anchor math),
`RepaintCoalescerTest` (a burst is one paint), the four progress-bar suites (`HudBarAssetCodecTest`,
`HudBarPanelAssetTest` incl. the default position, `HudBarSlotsTest` for which live rows get a slot,
fill rows sorting above item rows and the reading's fraction clamp, `HudBarLookTest` for the
override-over-display fold, an unauthored item's own row and the two row shapes the paint draws,
`HudBarDressingTest` for the dressing following the card's opacity and the Java mirrors matching
both documents), the card suites (`HudCardLookTest` for the hex in every spelling, the identity
pushing nothing, the own-over-shared fold and the dimming rule; `HudCardAssetTest` for the leaf, a
child under `Parent`, and the fold reading shipped until a record lands),
`SettingsFormTest` (field-spec render/refresh/collect round trip), `ZigSearchRowTest` (the scoped
value path, `carry` under an `@`-key and its refusal of a bare one, and the two button words being
authored in the shipped en-US file), and `DestinationsTest` (the
routing vocabulary's decode + dispatch + audit contract: a registered type's own fields, the
bare-string form as the same value, an unknown or mis-cased `Type` failing the read, a late
registration still taking effect, a throwing handler counted against its owner). The retint engine and
rich-button primitive have no unit coverage here; they are validated in-game per the general `.ui`
rule (`.ui` files are not compiled, validate in-game). `RewardToastLinesTest` pins the toast
engine's chip-to-row bridge (a chip becomes one row, its icon carries quantity one because the
label already says how many, and a list past the row budget spends its last row on the caller's
overflow line rather than cutting silently). `FeedbackEngineTest` covers the moment
schema's decode and inheritance, variant selection, `KeyArg`, the `EveryPercent` mark and the
audience question, plus everything a broken authoring file or an absent player could turn into a
throw; `ShippedFeedbackMomentsTest` decodes every shipped default and checks each line's key against
the shipped en-US lang file; `FeedbackMomentOverrideOrderTest` pins that a consumer's same-id file
replaces the library's through the engine map's own pack chain; `BroadcastScopeTest` pins who a
scoped banner admits, and the one-banner-per-key-per-window throttle. The drawing itself is packets
and is validated in game.
