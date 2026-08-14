# commerce/fold/ - the JOIN (module `zc-commerce`)

Router for `com.ziggfreed.common.commerce.fold`. The module's other two halves deliberately cannot
see each other: the ENGINE knows no codec, no store and no file, and the AUTHORING layer imports no
engine type. This package is the seam between them, and the only one allowed to see both - plus the
rest of the server, which is why the reward kind, the destinations and the owner-file readers live
here rather than in a half that would have to reach sideways for them.

Everything here is JOINING. Nothing here decides what a price means, what a draw does, or what an
author may write; each of those already has an owner one package over.

| Class | What it joins |
|---|---|
| [`CommerceFold`](CommerceFold.java) | the authored GROUPS to the runtime values: `Cost`, `RotationSpec`, `SelectionSpec`, `PoolSlot`, `RerollSpec`, `PurchaseLimits`, `RewardSpec`, `CurrencyDef` |
| [`ShopEntryOffer`](ShopEntryOffer.java) | one authored offer to the `ShopOffer` seam a purchase asks |
| [`BoardAssetSpec`](BoardAssetSpec.java) | one authored board to the `BoardSpec` seam a draw asks |
| [`BountyAssetRef`](BountyAssetRef.java) | one authored contract to the `BountyRef` seam a draw asks |
| [`AssetCurrencyCatalog`](AssetCurrencyCatalog.java) | the wallet fold to `CurrencyCatalog` |
| [`AssetShopCatalog`](AssetShopCatalog.java) | the offer store (generators expanded) to `ShopCatalog` |
| [`AssetBoardCatalog`](AssetBoardCatalog.java) | the board fold and the contract store to what the board engine takes per call |
| [`CommerceCatalogs`](CommerceCatalogs.java) | all three, plus the value-source seam a generator's axes read and the refresh the wiring root calls |
| [`CommerceDefaults`](CommerceDefaults.java) | what a BARE server's economy runs on: the state store and the currency engine, both producer-replaceable |
| [`CommerceOwnerLayers`](CommerceOwnerLayers.java) | the server owner's `mods/ziggfreedcommon/*.json` to each type's owner layer |
| [`CurrencyRewardKind`](CurrencyRewardKind.java) | the shared reward table to the currency engine (`{"Kind": "Currency"}`) |
| [`CommerceDestinations`](CommerceDestinations.java) | the shared routing vocabulary to this module's pages (`Shop` / `Board`) |

## The two rules that shape everything here

- **A degrade follows the AUTHORED leaf's documentation, not the engine seam's default.** Where the
  two differ, what an author reads in their own file is what happens - an unauthored `Rotation` never
  turns over, because that is what the leaf says, even though the engine seam's convenience default
  is daily. A validator reports the same bad leaf as a finding; the fold only has to keep the server
  running past it.
- **Total and fail-soft, one bad file at a time.** A fold runs over a whole catalogue, so a leaf
  nobody could have meant degrades that ONE value with a single line naming the file and never
  throws. A malformed owner entry costs itself; a malformed owner FILE costs the overrides.

## Rules to keep

- **A view, never a copy.** `ShopEntryOffer` / `BoardAssetSpec` / `BountyAssetRef` hold the asset
  they came from and expose it, so a surface that needs a title, an icon or a shelf label reads it
  there rather than from a second mirror. The half an engine asks about is folded once at
  construction and the whole object is rebuilt whenever the layer behind it is, so an authored price
  and a charged price cannot disagree - they only ever exist together.
- **Live where it can be, snapshotted where it cannot.** Wallets and boards resolve off their config
  fold on every question, so a reload lands with nothing to invalidate. Offers are the exception:
  a family written as one generator file has to be EXPANDED before it exists, and expansion needs a
  consumer's registered value sources, so the catalogue is rebuilt on `refreshShops()` instead.
- **Memoise against the asset INSTANCE, never on a timer.** Every layer merge replaces the asset
  objects wholesale, so identity is what says a memo is stale. That is why none of these needs an
  invalidation hook and why a stale answer cannot outlive a reload.
- **An owner override inherits from the PACK, every read.** The previous owner layer is dropped
  before anything is decoded, or a second read stacks on the first and a file that says one thing
  starts meaning another. Decoding through the type's OWN codec is what makes an override leaf by
  leaf, so an author needs no second schema.
- **ONE enumerator vocabulary, however many stores walk axes.** The rows behind `"yourmod:skills"`
  are registered once, and both the quest generators and the offer generators read the same list.
  `CommerceCatalogs.axisValuesOf` is the one line that says so; a consumer installs the same registry
  it hands the quest store.
- **A row value keeps its own type, so write it the way the field wants it.** A generator value that
  is exactly one token lands as that token's type: a reward parameter is text and wants a quoted row
  value, while a price and a requirement bound are numbers and want a bare one. It is the shared
  generator's ruled contract, and it is the one place a family silently writes nothing.
- **Registration belongs to the wiring root, not here.** Each class above exposes ONE method the root
  calls; nothing here registers itself, and nothing here is reached from a module below.

## Still to fill

- **The two pages.** `CommerceDestinations` registers `Shop` and `Board` NOW because an unknown
  `Type` fails an asset read, so content naming either could not be authored until they exist. Both
  handlers decline with one line at fine until the pages land; only those two bodies change then.
- **A retry command for the currency kind.** A failed wallet payout is reported and lost rather than
  queued for the next connect, because there is no command surface to replay it through yet. The
  commands wave closes it, and nothing else about the kind changes.

## Adding to it

- A new authored group joins the runtime the same way: one total, fail-soft method on `CommerceFold`,
  documented with what an unauthored leaf means and what a malformed one degrades to.
- A new catalog answers off the live fold unless something genuinely has to be expanded first, in
  which case it is a snapshot with an explicit refresh and the reason is written on the class.
- Tests are mechanics, structure, and invariants. Assert that the folded value IS the authored one
  rather than typing the number in: a balance pass must never be a test edit.
