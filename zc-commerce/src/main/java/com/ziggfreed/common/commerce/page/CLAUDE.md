# commerce/page/

- Emit every authored or convention key through zc-core's `i18n/ContentKeys`, never `Msg.key`: `I18nModule` prefixes each key with its `.lang` filename, so an authored key sent as written renders raw. `Msg.key` is only for a fully qualified id (this module's `ziggfreedcommon.commerce.*` chrome, a native `server.*` name).
- `CommercePageDeps` is the one deps object for both screens. Its `titleArgs` is where the mod owning generated ids names them: every authored argument is asked, and an unanswered one passes through as authored.
- A band or shelf label resolves through `CommerceLabels`: the board's `Grades` or the storefront's `Categories` entry, then the convention key, then the raw word.
- Take the `Subject` from the shared progression runtime (`ProgressionRuntime.subjects()`); a locally built one reads zero balances and silently drops writes.
- Every press re-asks the engine and never trusts the last render. Anything that could leave a player short (a lapse, a period lock, a reroll probe, the accept site) is engine behaviour, never a page decision.
- Build engines per call through `fold/CommerceEngines`, never hold one in a field: a reload replaces every offer, and a consumer may install its own store after this module's setup.
- A refusal token becomes words only in `CommerceRefusals`; gate tokens and unknown tokens delegate to zc-progression's `quest.LockReasons`. `CommerceRefusalsTest` finds every engine `REASON_*` constant by reflection and fails on one with no shipped en-US line.
- A partial update addresses rows only through the `BuiltRows` record of the last full build; when the index is gone, reopen in full.
- Action buttons bind once per build with no id in the binding and act on whatever the detail panel shows; a partial update can restyle an element but never add or change a binding.
- Every exit path sends a response (a reopen, a partial update or a close), or the client hangs.
- A consumer seam that throws costs only its own contribution, never the screen.
- A toast raised after a payout lists the grant receipt, never the authored rewards. The board hand-in toast is the gold line with rows when the contract paid here, and the plain success line with none when it parked.
- The storefront page opens closed on `!StorefrontAsset.isAvailable()`, and the board page on `!BoardAssetSpec.enabled()`, which reads the board's hide axis; the unnamed default is the configs' `firstListedId()`.
- The storefront page lists its standing offers through `AssetShopCatalog.availableOffersOf`, never `offersOf`.
- The board's Mine tab lists through `BoardEngine.namingBoard`, never `membersOf`.
