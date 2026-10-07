# commerce/page/

- Emit every authored or convention key through zc-core's `i18n/ContentKeys`, never `Msg.key`: `I18nModule` prefixes each key with its `.lang` filename, so an authored key sent as written renders raw. `Msg.key` is only for a fully qualified id (this module's `ziggfreedcommon.commerce.*` chrome, a native `server.*` name).
- `CommercePageDeps` is the one deps object for both screens. Its `titleArgs` is where the mod owning generated ids names them: every authored argument is asked, and an unanswered one passes through as authored.
- A band or shelf label resolves through `CommerceLabels`: the board's `Grades` or the storefront's `Categories` entry, then the convention key, then the raw word.
- A grade word's colour is `CommerceLabels.gradeInk`: the band's `BoardSpec.gradeColor`, else the surface's own; a surface never reads an authored colour unclamped.
- Take the `Subject` from the shared progression runtime (`ProgressionRuntime.subjects()`); a locally built one reads zero balances and silently drops writes.
- Every press re-asks the engine and never trusts the last render. Anything that could leave a player short (a lapse, a period lock, a reroll probe, the accept site) is engine behaviour, never a page decision.
- Build engines per call through `fold/CommerceEngines`, never hold one in a field: a reload replaces every offer, and a consumer may install its own store after this module's setup.
- A refusal token becomes words only in `CommerceRefusals`; gate tokens and unknown tokens delegate to zc-progression's `quest.LockReasons`. `CommerceRefusalsTest` finds every engine `REASON_*` constant by reflection and fails on one with no shipped en-US line.
- A partial update addresses rows only through the `BuiltRows` record of the last full build; when the index is gone, reopen in full.
- Action buttons bind once per build with no id in the binding and act on whatever the detail panel shows; a partial update can restyle an element but never add or change a binding.
- Every exit path sends a response (a reopen, a partial update or a close), or the client hangs.
- A consumer seam that throws costs only its own contribution, never the screen.
- A toast raised after a payout lists the grant receipt, never the authored rewards. The board hand-in toast is the gold line with rows when the contract paid here, and the plain success line with none when it parked.
- Both pages open closed on `!isAvailableIn(WhereAxis.viewer(store))` (the storefront's or the board's asset: switched off, hidden by a feature, or outside its `Where` for the world the page is built in), and `CommercePages.openShop`/`openBoard` take the unnamed default from `firstShopId(viewer)`/`firstBoardId(viewer)`; the no-argument forms are the viewer-less view. A press that would act on a page whose storefront or board is not in the presser's world (`!existsIn`) acts on nothing: it refuses as `disabled` and reopens closed.
- The storefront page sells through `CommerceEngines.shopsAt(shopId)` and reads its standing offers, shelves, category order and names and header wallets through `commerce/fold/StorefrontView` (its own and every storefront it `Includes`), never `CommerceEngines.shops()`, `offersOf` or a storefront leaf directly.
- The board's Mine tab lists through `BoardEngine.namingBoard`, never `membersOf`.
