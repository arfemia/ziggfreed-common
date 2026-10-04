# commerce/fold/ - the join between authored files and the engines

- A fold is total and fail-soft: a bad leaf degrades that one value with one line naming the file and never throws. An unauthored leaf means what the authored leaf documents (an unauthored `Rotation` never turns over), not what the engine seam defaults to.
- `ShopEntryOffer`, `BoardAssetSpec` and `BountyAssetRef` are views that hold their asset and are rebuilt with its layer, never copies.
- Wallets and boards resolve live. Offers are a snapshot rebuilt by `CommerceCatalogs.refreshShops()`, because expanding a generator needs a consumer's registered value sources.
- Memoise against the asset instance, never on a timer, and build an engine per call through `CommerceEngines`, never hold one in a field.
- A consumer installs `CommerceEngines.installGates`, or every factor `Requires` fails closed. Without `installRetryQueue` a failed reward is reported lost, and a partial delivery is never refunded.
- `CommerceCatalogs.publishBounties()` publishes contracts under their own `CONTRACTS_SLICE`; until it runs, a board can draw contracts but cannot accept them.
- A generator row value keeps its token type: quote a reward parameter, and write a price or a requirement bound bare.
- Registration belongs to the wiring root: nothing in this package registers itself.
- `ShopEntryOffer.enabled()` reads its own hide axis and its storefront's live, so a press on a page drawn before either was hidden refuses as `disabled` rather than sells; `requires()` is the offer's lock only.
