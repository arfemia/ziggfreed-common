# commerce/page/ - the two screens (module `zc-commerce`)

Router for `com.ziggfreed.common.commerce.page`. A storefront and a board, generic, driven entirely
through this module's own engines and the shared quest runtime, so a bare server with authored
content gets both screens working with no consumer at all.

**It sits beside [`commerce/fold/`](../fold/CLAUDE.md) rather than inside `shop/` or `board/`, and
for the same reason.** A page needs BOTH halves of this module - the engines to ask what may happen,
the authoring layer to know what a thing is called and what it looks like - plus the presentation
layer above them. The engine halves may never see a codec or a file, so a page cannot live in one;
this is the second package allowed to see both.

| Class | What it is |
|---|---|
| [`CommercePages`](CommercePages.java) | the way in: `openShop` / `openBoard`, and the one place a consumer registers its deps |
| [`CommercePageDeps`](CommercePageDeps.java) | everything a consumer may say and nothing it must, every seam defaulted |
| [`ZigShopPage`](ZigShopPage.java) | the storefront: rotating shelves, then the standing catalogue, with the offer being read on the right |
| [`ZigBoardPage`](ZigBoardPage.java) | the board: what is posted, what the player carries from it, and every lifecycle affordance a contract has |
| [`ShopSections`](ShopSections.java) / [`BoardSections`](BoardSections.java) | the PURE ordering cores: which run a row belongs in, in what order the page reads, and which row the panel opens on |
| [`CommerceText`](CommerceText.java) | authored text as a client-resolved message, the title-argument seam, and the countdown |
| [`CommerceRefusals`](CommerceRefusals.java) | one refusal TOKEN as a line a player reads |
| [`CommerceChips`](CommerceChips.java) | the two repeatable things both pages paint: a chip and a line |
| [`CurrencyText`](CurrencyText.java) | what a wallet is CALLED, on a three-rung ladder |
| [`ConfirmArm`](ConfirmArm.java) | two clicks before anything charges |
| [`ShopEventData`](ShopEventData.java) / [`BoardEventData`](BoardEventData.java) | what each page round-trips on a binding |

## The title-argument seam, and why it is the one a consumer really should fill

A generated family writes one file per row, and the thing that varies is usually a NAME:
`"TextArgs": { "Title": ["MINING"] }` against a line reading `"{0} experience"`. That argument is an
ID, so passing it through renders `MINING experience` in every language at once - a shipped-content
bug no translation can fix, and one that reads as correct in the content file.

`CommercePageDeps.titleArgs` is where the mod that OWNS those ids says what one means, once. The
resolver is asked about EVERY authored argument rather than only the `@`-prefixed sentinels, because
a generated id is written bare; an unanswered one passes through exactly as authored, which is how
an author finds out they wrote something nothing provides.

## Rules to keep

- **ONE deps object for both pages.** A storefront and a board name the same wallets, read the same
  generated content, paint the same reward chips and wear the same theme. Splitting them would make
  a consumer say all of that twice and let the two copies drift.
- **The SUBJECT comes from the shared progression runtime, never built here.** A subject built
  locally carries no handle the installed stores recognise, so every balance reads zero and every
  write is silently dropped - a purchase that reports success and changes nothing. It is also what
  lets a board's contracts and a storefront's wallet belong to the same player.
- **Every press re-asks the engine.** A screen is a snapshot: an offer rotates out, a limit fills, a
  period turns over. Nothing here trusts what it last rendered, and a stale press answers with the
  engine's own refusal rather than charging.
- **A refusal is turned into words HERE, in one place.** Both pages read `CommerceRefusals`, so one
  sentence cannot have two wordings. A GATE refusal deliberately reads as the generic locked line
  rather than leaking whatever an author gated on - the same rule the objective book and the NPC
  quest page follow.
- **A partial update must target an index the last full BUILD recorded.** `builtRowOrder` carries a
  marker per section heading, because a recomputed index ignores headings, can land on one, and an
  unresolved selector disconnects the player. Every path falls back to a full reopen when the
  recorded index is gone.
- **The action buttons are bound ONCE per build with no id in the binding.** They act on whatever the
  detail panel shows, so a partial swap of the panel needs no rebuild - a page update can restyle an
  element that exists but can never add or change a binding.
- **EVERY exit path sends a response** - a reopen, a partial update, or a close - or the client spins
  forever.
- **A consumer seam that throws costs its own contribution, never the screen.** Theme, naming,
  chips, toasts and the hand-off are each guarded on their own.
- **The engines are built PER CALL** through [`fold/CommerceEngines`](../fold/CommerceEngines.java),
  never held in a field: a reload replaces every offer object, and a consumer may install its own
  store or currency engine after this module's setup ran.

## What a page owns and what an engine owns

The split is worth stating because it is what stops a shipped bug coming back. The lapse re-arm, the
period lock, the pre-charge reroll probe, the one-day-number purchase and the accept SITE are all
ENGINE behaviour these pages merely call. A page decides what a run is called, which row is
highlighted, and what a refusal reads as. Nothing that could leave a player short is a page's
decision.

## The `.ui` contract

`Pages/ZigShopPage.ui` and `Pages/ZigBoardPage.ui`, each appending `Pages/ZigCommerceRow.ui` for
list rows AND section headings (one template, because a list mixing two gives two different child
sets at one index), plus `Pages/ZigCommerceLine.ui` for a detail line and
`Pages/ZigCommerceChip.ui` for a balance or price chip. All text lands on `.TextSpans`; every
labeled button is `Button` + `#Label` driven by `ZigRichButton`, never a `TextButton`.

`.ui` files are not compiled, so a green build says nothing about them: both pages are in-game smoke
territory until a maintainer opens them.

## Keys

`Server/Languages/<locale>/ziggfreedcommon.commerce.lang`, all nine locales, in-file keys dropping
the `commerce.` segment the filename carries. It holds CHROME ONLY: a shop's name, a category label
(`shop.category.<id>`), a contract's grade (`board.grade.<id>`) and every offer or contract title are
CONTENT keys belonging to whoever authored the content. Nothing in that file names a currency, a
price, or any other balance figure.

## Tests

Pure decision cores only, matching the rest of the library. `ShopSectionsTest` and
`BoardSectionsTest` pin the ordering a player notices immediately and a refactor breaks silently;
`CommerceRefusalsTest` DISCOVERS both engines' `REASON_*` constants by reflection and fails when one
is unmapped or names a key the English file does not ship, which is what makes a new refusal a
failing test rather than a quiet degrade; `CommerceTextTest` pins the argument seam and the
countdown; `ConfirmArmTest` pins the window. The rendering itself is in-game smoke.
