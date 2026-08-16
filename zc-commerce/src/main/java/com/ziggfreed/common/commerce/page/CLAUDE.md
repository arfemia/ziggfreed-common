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
| [`CommerceLabels`](CommerceLabels.java) | what a difficulty BAND and a shelf CATEGORY are called, on the one ladder both screens read |
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

## An authored key is never emitted as written

`I18nModule` namespaces a key by the `.lang` FILENAME it was defined in, so an entry written
`shop.general.title` inside `mmoskilltree.lang` registers as `mmoskilltree.shop.general.title`.
Content authors the key WITHOUT that namespace, so anything here that hands a client the authored
key hands it an id nothing resolves - and the player reads the key itself. That shipped once: every
board title, shop title, contract title and category label rendered raw.

So every authored or convention key on these screens goes through zc-core's `ContentKeys`
(`CommerceText.title`/`flavor`, `CurrencyText.nameOf`, `ZigBoardPage`'s step keys, and both label
families through `CommerceLabels`), never `Msg.key`. `Msg.key` stays correct for a FULLY-QUALIFIED id
only - this module's own `ziggfreedcommon.commerce.*` chrome through `text(...)`, or a native
`server.*` name. A key nothing claims passes through exactly as authored, so a server with no
consumer registered behaves as it always did.

## A band and a shelf are named on a LADDER, and this module holds the bottom rung

`board.grade.<id>` and `shop.category.<id>` were synthesized from a word the content invented and
handed straight to a client, so a band nobody had translated rendered as its own key on the screen.
`CommerceLabels` is the one answer both screens (and any consumer surface printing a grade) ask:

1. the AUTHORED key - a board's own `Grades` entry for its band, a storefront's `Categories` entry for
   its shelf - which is how a pack inventing a band supplies its own word with no Java;
2. the CONVENTION key when a consumer ships one, so a mod that already wrote `board.grade.veteran`
   in its own lang file keeps it;
3. this module's own shipped default for the common bands and shelves;
4. the raw word, which is readable and is the visible sign that nobody named that band.

Rung 3 is why this module probes its own catalogue DIRECTLY rather than registering itself as a
`ContentKeys` fill: a library fill would sit in the same queue as its consumers and, since the
library loads first, would answer ahead of every one of them - which is the wrong way round.

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
the `commerce.` segment the filename carries. It holds CHROME ONLY: a shop's own name and every offer
or contract title are CONTENT keys belonging to whoever authored the content. Nothing in that file
names a currency, a price, or any other balance figure.

The DEFAULT band and shelf labels at the bottom of that file are chrome under the same rule: one word
for a common band or shelf, carrying nothing about what anything costs or is worth. They are the
bottom rung of the ladder above, so authored content and a consumer's own key both outrank them; a
new one is added only when it names a word content commonly invents, and never when it would name a
wallet, a price, or a tier's worth.

## Tests

Pure decision cores only, matching the rest of the library. `ShopSectionsTest` and
`BoardSectionsTest` pin the ordering a player notices immediately and a refactor breaks silently;
`CommerceRefusalsTest` DISCOVERS both engines' `REASON_*` constants by reflection and fails when one
is unmapped or names a key the English file does not ship, which is what makes a new refusal a
failing test rather than a quiet degrade; `CommerceTextTest` pins the argument seam and the
countdown; `CommerceLabelsTest` pins which key a band is printed under, including the one that
matters - that a band nothing names falls back to the word rather than to a key; `ConfirmArmTest`
pins the window. The rendering itself is in-game smoke, and so is the shipped-default rung of the
label ladder, which is an engine catalogue lookup a unit JVM answers "no" to.
