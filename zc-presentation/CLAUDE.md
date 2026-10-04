# zc-presentation

- A `ui/` primitive never imports dialogue, instance or progression content: domain surfaces depend on presentation, never the reverse.
- A primitive that paints a consumer's words takes pre-built `Message`s; only a widget the library ships whole (the search row) labels itself from `ziggfreedcommon.ui.lang`.
- A `Notify` tag is the only thing that merges repeated notices: use one tag per wording, and never one tag on two notices a player must both read, since the second replaces the first.
- The library ships twelve neutral `FeedbackMoments`: the seven its engines announce, the four the boss framework announces and `Gear_Set_Tier`, which the root's `gearset/GearSetNoticeBridge` fires off `ZigGearSetTierChangedEvent` (Reward tone with the tier's line as `Secondary`, and a quiet Info variant for `active: false`).
- A feedback moment with no file does nothing, and a consumer's same-id file replaces the library default by pack order (`FeedbackMomentOverrideOrderTest`). Author a line's `Key` without a namespace; `FeedbackMomentAsset`'s javadoc is the authoring reference.
- Every shipped moment line needs its key in the en-US `ziggfreedcommon.feedback.lang` (`ShippedFeedbackMomentsTest`).
- A page writes a `.Text` property only through `UiText` (`TextSinkGoesThroughUiTextTest`): any `Message` other than a translation in that slot disconnects the client.
- A binding carrying a live element value uses an `@`-prefixed key (`"@SearchInput"`), declared the same way on the receiving codec; `SettingsUiUtil.directive` refuses a bare one.
- Never bind a search field per keystroke (a rebuild steals focus); use `ZigSearchRow`, whose `carry` puts the live text on your other bindings.
- `Pages/ZigSelectRow.ui` and `Pages/ZigDetailLine.ui` are the one list row and the one detail line for every page in the family; their sizes live only in those files. Each ships a hidden picture slot (`#RowIconSlot`, `#LineIconSlot`) that a page fills through `ui/icon/IconRenderer`, setting the slot's `.Visible` from its return; a page that paints none leaves it hidden, and so does a section heading.
- Register a `ui/route/Destination` type in `setup()`, before assets load: an unknown `Type` fails the read, naming the file.
- `UiDocumentSyntaxTest` checks ids and brace balance for this module's documents only (the id rule is in the zc root).
