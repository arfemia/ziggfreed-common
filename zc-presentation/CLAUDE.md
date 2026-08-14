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
- `feedback/` - `Notify` (Default/Danger/Warning/Success toasts + the item-keyed stacking form),
  `EventTitles` (centered banner), `PickupMimic` (native-pickup-mimic notifier for a programmatic
  item grant that never went through a real ground pickup), `ObjectiveHud`. No router of its own
  (four files); see the root router's `feedback/` bullet for the full primitive list.
- [`sound/`](src/main/java/com/ziggfreed/common/sound/CLAUDE.md) - `Sound3D`.
- `ui/` - `CustomHudHelper`, `ZigRichButton` (the clickable-rich-text primitive every labeled
  button in the library uses), `UiRetint` (the generic palette-to-selector retint primitive),
  `SettingsUiUtil` (settings-form binding helper). No router at this top level (mixed
  single-file primitives); the four structured subpackages below each have their own:
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
ZigToast.ui}` plus `ZigToastFrame.png`. No `Server/` content; this module ships presentation only.

## Conventions

Every labeled clickable button is a `ZigRichButton` (a `Button` + inner `#Label` set via
`.TextSpans`), never a `TextButton` whose `.Text` is set from Java - see the root router's hard
rule under Conventions for why. Colour/bold/param substitution only render on `.TextSpans`, never
`.Text`. i18n is parameterized OUT of every primitive here (pre-built `Message`s only); nothing in
this module owns a namespace prefix.

## Tests

Thin relative to the package count: `HudPositionTest` (corner-preset parsing + anchor math),
`SettingsFormTest` (field-spec render/refresh/collect round trip), and `DestinationsTest` (the
routing vocabulary's decode + dispatch + audit contract: a registered type's own fields, the
bare-string form as the same value, an unknown or mis-cased `Type` failing the read, a late
registration still taking effect, a throwing handler counted against its owner). The toast engine,
retint engine,
and rich-button primitive have no unit coverage here; they are validated in-game per the general
`.ui` rule (`.ui` files are not compiled, validate in-game).
