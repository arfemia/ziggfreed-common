# CLAUDE.md - `currency/asset/` (module `zc-commerce`)

The AUTHORING layer over the currency engine: what a wallet is, as a file.

Store path (registered ONCE by the root `asset/FrameworkAssetRegistrar`, common OWNS it):
`Server/ZiggfreedCommon/Currencies/<ns>/<Id>.json` -> `CurrencyAsset` -> `CurrencyConfig`, owner layer
`mods/ziggfreedcommon/currencies.json` (read by
[`commerce/fold/CommerceOwnerLayers`](../../commerce/fold/CLAUDE.md) off the same load event, since
an override has nothing to inherit from until the pack layer has landed). The folder under the type
root is plain organization; the FILE NAME is the id.

What the currency ENGINE reads is folded out of this by
[`commerce/fold/`](../../commerce/fold/CLAUDE.md), which is also where the name ladder below turns
into the one `nameKey` field a `CurrencyDef` keeps.

## The pieces

| Class | What it is |
|---|---|
| `CurrencyAsset` (+ `.Backing`/`.OnDeath`/`.Decay`) | one wallet: what it looks like, what backs it, and its economy knobs |
| `CurrencyConfig` | the `defaults < pack < owner` fold, plus `isSpendable` and the enabled listing |
| `CurrencyValidator` | the content audit; shared `validation.Finding` values under domain `commerce` |

## Rules to keep

- **Backing is the one real choice, and nothing else changes with it.** Author `Backing.Item` and the
  balance IS an inventory count - carried, tradable, and subject to whatever the world already does
  with items on death. Leave it out and the balance is a number this server keeps. Every other leaf
  reads the same either way, and **nothing that spends a wallet ever branches on which kind it is**;
  that dispatch belongs to the engine and to nowhere else.
- **`Icon`, and only `Icon`.** It is an item ID whose picture stands for the wallet; a texture path is
  not a second leaf, because the picture a chip renderer can show is an item's. An item-backed wallet
  authors none at all - `effectiveIconItemId()` falls through to the backing item, which is also
  where its NAME comes from when no key is written.
- **`Cap`, `OnDeath` and `Decay` are three independent knobs**, each unauthored meaning "no such
  rule". A wallet with none of them is a permanent balance. They are nested nullable groups rather
  than flat prefixed keys so a later economy knob lands beside its siblings.
- **A share leaf is a FRACTION, 0 to 1.** `LossPercent: 0.1` takes a tenth. Anything outside the range
  is clamped AND reported, because a 10 meaning "ten per cent" would otherwise silently wipe a wallet.
- **`Requires` on a wallet gates VISIBILITY, never earning.** It decides whether a player is shown the
  balance at all; it must not be used to stop them accruing one, since a balance nobody can see still
  has to be correct when it is finally revealed.
- **A knob only one mod understands goes in `Meta`** under that mod's namespace - a scoreboard slot,
  an experience conversion rate. Nothing here interprets those, which is what lets one wallet file
  load on a server running only one of the two mods that authored it.
- **A wallet nothing defines does not exist**, and everything priced in it is unaffordable rather than
  free. That is the safe direction, and it is why an unknown currency id is a reported WARNING at
  every price site rather than a silence.
- **A `$Comment` in one of these files is a TIP for the server owner or pack author.**

## Adding to it

- A new economy knob: a nested nullable group beside `OnDeath` and `Decay`, documented for an author
  (what it does in game, what unauthored means).
- A new finding: add it to `CurrencyValidator` with a stable code; unknown means warning, impossible
  means error.
- Tests are mechanics, structure, and invariants. Fixture content is author-owned; never assert
  numbers that belong to somebody's balance pass.
