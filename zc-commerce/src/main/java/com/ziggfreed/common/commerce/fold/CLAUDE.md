# commerce/fold/ - the join between authored files and the engines

- A fold is total and fail-soft: a bad leaf degrades that one value with one line naming the file and never throws. An unauthored leaf means what the authored leaf documents (an unauthored `Rotation` never turns over), not what the engine seam defaults to.
- `ShopEntryOffer`, `BoardAssetSpec` and `BountyAssetRef` are views that hold their asset and are rebuilt with its layer, never copies.
- Wallets and boards resolve live. Offers are a snapshot rebuilt by `CommerceCatalogs.refreshShops()`, because expanding a generator needs a consumer's registered value sources.
- Memoise against the asset instance, never on a timer, and build an engine per call through `CommerceEngines`, never hold one in a field.
- A consumer installs `CommerceEngines.installGates`, or every factor `Requires` fails closed. Without `installRetryQueue` a failed reward is reported lost, and a partial delivery is never refunded.
- `CommerceCatalogs.publishBounties()` publishes contracts under their own `CONTRACTS_SLICE`; until it runs, a board can draw contracts but cannot accept them.
- A generator row value keeps its token type: quote a reward parameter, and write a price or a requirement bound bare.
- Registration belongs to the wiring root: nothing in this package registers itself.
- `ShopEntryOffer.enabled()` reads its own hide axis and, live, the storefront it stands in: its own, or for a view from `at(host)` the including host, whose lock `storefrontRequires()` reads too; so a press on a page drawn before either was hidden refuses as `disabled` rather than sells, and `requires()` is the offer's lock only. `StorefrontView` is the one reader of `Includes` and hands out those host-bound views (its `catalogAt` is what `CommerceEngines.shopsAt` sells through).
- `AssetBoardCatalog.boards()` is the viewer-less board list (admin verbs, server-wide questions); a player's list is `boardsIn(viewer)`, which drops a board whose `Where` leaves that player's world out. The engine views (`BoardAssetSpec.enabled()`, `ShopEntryOffer.enabled()`) have no viewer and never read `Where`.
- No audit line names an id the mod gate refused (a store's `modGateRefused()`: a gated pack file or an owner take-out): the shop and board validators, `CommerceAudit`'s wallet probe (`CurrencyProbe.refused`) and the destination checks skip it, while an id nothing refused still warns.
- `CommerceAudit.auditSeasons` reports an authored file whose `Season` no loaded calendar event declares (`UNKNOWN_SEASON`, WARNING, in the wallet, shop or board domain), once per file and never per generated offer.
- The boot-time commerce pass runs once per boot: at the first `PlayerReady` (`runLateAudit`), or at `BootEvent` under zc-core's boot-audit switch (`claimLateFindings`, which takes the same flag, so the pass never prints twice).
