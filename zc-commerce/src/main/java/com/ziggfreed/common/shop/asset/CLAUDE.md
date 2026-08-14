# CLAUDE.md - `shop/asset/` (module `zc-commerce`)

The AUTHORING layer over the shop engine: how a storefront is written as a file, how one offer is,
how a family of offers is written as one file, and what a content audit reports. The engine beside it
knows none of this.

Store paths (registered ONCE by the root `asset/FrameworkAssetRegistrar`, common OWNS them):
- `Server/ZiggfreedCommon/Shops/<ns>/<Id>.json` -> `ShopAsset` -> `ShopConfig`
- `Server/ZiggfreedCommon/ShopPools/<ns>/<Id>.json` -> `ShopPoolAsset` -> `ShopPoolConfig`
- `Server/ZiggfreedCommon/ShopEntries/<ns>/<Id>.json` -> `ShopEntryAsset` -> `ShopAssetStore`
- `Server/ZiggfreedCommon/ShopEntryGenerators/<ns>/<Id>.json` -> `ShopEntryGeneratorAsset` (loads
  AFTER ShopEntries)

The folder under a type root is plain organization; the FILE NAME is the id, so two files of the same
name in different folders are one id and the store reports the clash.

## The load path, end to end

```
files      -> asset store (resolves Parent natively)  -> ShopAssetStore.mergeEntries
generators                                            -> ShopAssetStore.mergeGenerators
storefronts / shelves                                 -> ShopConfig / ShopPoolConfig (defaults < pack < owner)
owner file mods/ziggfreedcommon/shops.json            -> ShopConfig.mergeOwnerLayer
                     |
       store.resolveAll(axisValues)  -> expand generators -> decode each generated body
                                        against its Base through THE SAME codec
                     |
                  the folded catalogue -> commerce/fold/AssetShopCatalog -> the shop engine
                                       -> ShopValidator.validate(entries, shops, pools, ...)
```

The load event drives all of it: the wiring root merges each layer, re-reads the owner file, and
rebuilds the catalogue, so a re-import lands with nothing to invalidate. The `axisValues` a consumer
registers are the SAME ones the quest generators read - see
[`commerce/fold/`](../../commerce/fold/CLAUDE.md).

## The pieces

| Class | What it is |
|---|---|
| `ShopAsset` | one storefront: text, icon, order, the header wallets, the shelf order, `Requires`, `Where` |
| `ShopPoolAsset` | one rotating shelf: the shared `Rotation` / `Selection` / `Reroll` groups plus its slots |
| `PoolSlotAsset` | one slot of a shelf: the shared slot leaves plus `Tier` |
| `ShopEntryAsset` (+ `.Listing`/`.Limits`/`.PoolMembership`) | one offer: price, payout, limits, shelf membership, gate |
| `ShopEntryGeneratorAsset` | one file writes a whole family of offers |
| `ShopConfig`, `ShopPoolConfig` | the `defaults < pack < owner` folds, with the owner layers `mods/ziggfreedcommon/shops.json` and the pools' own |
| `ShopAssetStore` | the loaded offers + generators, and the fold into one catalogue |
| `ShopValidator` (+ `.CurrencyProbe`) | the content audit; shared `validation.Finding` values under domain `shop` |
| [`commerce/asset`](../../commerce/asset/CLAUDE.md) | the groups shared with boards: `Cost`, `Rotation`, `Selection`, `Slot`, `Reroll` |
| [`progress/asset`](../../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/progress/asset/CLAUDE.md) | the groups shared with quests and achievements: `Text`, `Listing`, `Rewards`, `Meta`, and the generator core |
| [`progress/gate`](../../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/progress/gate/CLAUDE.md) | `Requires`: the same block a quest carries, and the same audit |

## Rules to keep

- **Inheritance is NATIVE, and there is no template DSL.** An offer declares `"Parent": "<id>"` and
  the engine's own asset loading merges it. Every leaf is `appendInherited`, which is what lets a
  child retune a price and keep the payout it did not mention.
- **`Abstract` is the ONE field that must never inherit.** A child of a skeleton is a real offer, and
  inheriting it would take every child of every base off the page.
- **`Requires` is the SHARED gate block, verbatim.** A shop gate is the same `GateSpec` a quest
  carries - factor bounds, a permission, finished quests, a registered kind - and its findings come
  from the SHARED `GateValidator`. There is no shop-only requirement vocabulary, and adding one would
  mean an author learning two spellings of the same lock. A capability a real shop gate needs is
  added to `progress/gate` so every domain gains it at once.
- **The generator merges NOTHING.** It writes ordinary child bodies carrying `Parent` and lets the
  same decode do the rest, through the SHARED `progress/asset/GeneratorCore` a quest generator uses.
  Substitution covers every string value, every object KEY and `IdPattern`; a value that is exactly
  one token keeps that token's type; a token nothing binds is an ERROR and that one offer is skipped.
  Substituting a KEY is load-bearing rather than a nicety - it is how a per-skill requirement names
  its own stat channel with no escape hatch on the schema.
- **A row value keeps its own TYPE, so write it the way the field it fills wants it.** An axis row's
  `"xp": "1500"` is quoted because a reward's parameters are text, while its `"tokens": 75` is not
  because a price is a number. Get it the wrong way round and the whole family fails to decode and
  is reported - which is the one way a generator ships nothing at all rather than something wrong.
- **A ladder is `Listing.Chains`, not a layout switch.** A family of near-identical offers shown as
  ONE climbing entry is what the shared chain membership already models, so a storefront needs no
  grouping mode and an author needs no second vocabulary.
- **Display text is keys.** `Text.TitleKey` / `FlavorKey` are localization keys the player's own
  client resolves; `TextArgs` is how one written line serves a whole ladder. Never route shipped
  content through `DisplayName`.
- **An id is what a player's purchase count is filed under**, so renaming one - including by widening
  a generator's `IdPattern` - starts that count over.
- **A `$Comment` in any of these files is a TIP for the server owner or pack author.**

## Adding to it

- A new offer field: a leaf in the group it belongs to, `appendInherited`, documented for an author.
  A cohesive pair or trio is a new nested group, never a flat prefixed key.
- A new requirement: prefer a registered `GateKind` in `progress/gate` over a new leaf.
- A new finding: add it to `ShopValidator` with a stable code, and pick the severity by the rule at
  the top of that class - unknown means "some mod may supply it later" (warning), impossible means an
  error.
- Tests are mechanics, structure, and invariants. Fixture content is author-owned; never assert
  numbers that belong to somebody's balance pass.

## What became impossible by construction

Worth knowing, because these are bug classes rather than findings: a typo'd membership label can no
longer orphan an offer or skew a draw (membership is typed and validated, not packed into a string);
there is no second price spelling to disagree with the first; there is no second rotation schema for a
shelf and a board to drift apart on; and a generator can substitute an object KEY, so the workaround
that existed because it could not is gone with it.
