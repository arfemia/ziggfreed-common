# objectives/ - the library's own parts of the shared runtime (module `zc-objectives`)

Router for `com.ziggfreed.common.objectives`. What a BARE server gets: persistence for the shared
quest and achievement runtime, generic native-event producers feeding it, the asset content folded
into it, and an ordinary item that reads it in game.

Module edges: `zc-core`, `zc-loot`, `zc-progression`, `zc-presentation`, `zc-cast`, `zc-entity` -
all one-way `implementation`. Package root `com.ziggfreed.common.objectives`.

**Why this module exists at all.** The book needs BOTH the engines and a page. `zc-progression` may
never import presentation (its own router states the rule), and pushing the engines under
presentation would drag them onto every page consumer in the library. A module sitting ABOVE both
adds no reverse edge, which is the only shape that contradicts nothing.

## The one runtime: the book, the producers and every consumer read the same pair

There is ONE `QuestEngine` + `AchievementEngine` pair per server, held by
[`progress/runtime/`](../../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/progress/runtime/CLAUDE.md).
[`runtime/ProgressionDefaults`](runtime/ProgressionDefaults.java) registers THIS library's parts
into it - store, subjects, factor vocabulary, asset gate, both halves of a hand-in, the text a
surface names content by - all at LIBRARY-DEFAULT rank, so a consumer that brings its own is silently
in charge. Nothing here decides whether to run.

**Registration is unconditional; DISPATCHING is not.** The progress component TYPE and the four
producer systems are registered at `setup()`, because a component type registered after a world has
loaded cannot be read off entities saved carrying it and an ECS system is a setup-time registration.
Where a consumer owns the stores, nothing attaches the component (the connect handler checks
`ProgressionRuntime.usesDefaultStores()`, which is final at setup) and each producer it REPLACED
returns on its first line, so the cost is one map read per event.

**The stand-down is PER KIND, not global.** A consumer claims the objective kinds its own event
systems fire (`producesKind`), and only those producers stand down. **The day a fifth generic
producer is added here, every consumer's claim set must be extended in the same change** - a kind
nobody claims is fired twice, and a kind wrongly claimed is fired by nobody with nothing logged.

## The pieces

| Package | What it is |
|---|---|
| `runtime/` | `ProgressionDefaults`: the default registrations, the asset fold + its audit, the text source, the player lifecycle |
| `store/` | `ZigProgressComponent` (the persisted state) + `ProgressBlob` (the packing) + `ProgressHandle`/`ProgressSubjects` (the subject) + `ZigQuestStore`/`ZigAchievementStore` (the two adapters) |
| `producer/` | `ProgressDispatch` plus the four generic producers: block break, mob kill, craft, pickup |
| `book/` | the in-game two-tab surface and the item that opens it |
| `questlist/` | the NPC quest page: what one CHARACTER has to offer, list and detail |

## What these defaults MUST wire, because nothing works without them

Three seams look optional from inside `ProgressionDefaults` and are not. Each has a silent failure
mode: the surface reports success and the player receives nothing, which is invisible from every
side except their inventory.

- **Both halves of a hand-in**, `possessionProbe` and `inventoryConsumer`, over
  [`zc-core`'s `inventory/InventoryUtil`](../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/inventory/CLAUDE.md).
  The engine's defaults refuse everything, so an engine built without them takes nothing however
  much the player carries while the book still offers the Hand in button, because the step really is
  outstanding.
- **A handle that answers for the live `Player`.** Every ready-made reward kind in `zc-loot`
  resolves its player as `subject.handleAs(Player.class)`, and this runtime attaches a `ProgressHandle`
  instead, so the handle implements `Subject.HandleFacets` and stands in for the player itself. A
  handle that answered only for itself would leave every collected quest paying out nothing.
- **A permission probe over that same handle.** The library's ready-made probe expects the handle to
  BE a `PlayerRef`, so `ProgressHandle` stands in for that too.

## What these defaults deliberately do NOT supply

Both are choices to be read as answers, not gaps to be quietly filled:

- **No reward retry queue.** There is no per-player retry store here, so a reward that fails to
  grant is reported through `SafeLog` and lost rather than queued for the next connect.
- **No objective vocabulary of its own.** The kind registry gets the engine-generic built-ins and
  nothing else: a standalone runtime that invented domain kinds would be shipping content.

Points MILESTONES used to be on that list and no longer are. They have an asset source now
(`Server/ZiggfreedCommon/AchievementMilestones/`), so `publishAssetContent` publishes the folded
ladder at library-default rank and a bare server with milestone files gets its payouts. A consumer
publishing its own layer outranks it rung by rung, by threshold.

## The store

ONE persisted component, TWO adapters. Two because `QuestProgressStore.status(Subject, String)` and
`AchievementProgressStore.status(Subject, String)` share an erasure and differ in return type, so no
single class can implement both.

- The maps live on [`store/ZigProgressComponent`](store/ZigProgressComponent.java) and the adapters
  are a lookup plus a call, which is what keeps the behaviour unit-testable with no server.
- **Ten packed string leaves, not ten map codecs.** Each map travels through
  [`store/ProgressBlob`](store/ProgressBlob.java) as `key=value|key=value`, values base64-encoded
  where they are opaque (a quest payload may itself contain both separators). **That wire form is a
  contract** - it is what every saved world holds - so changing the spelling is a data migration.
- Because `|` and `=` are the only characters the format reserves, the INHERITED
  `usesReservedDelimiter` defaults on both seams are already exactly right. Neither adapter
  overrides it, and neither should.
- **A read never creates.** No component means neutral reads and dropped writes with one fine-level
  line. The single create path is `PlayerConnectEvent`, the one lifecycle hook carrying a `Holder`.
- **A new leaf is APPENDED, never inserted.** A blob saved before `QuestCompletions` existed simply
  has no value for it and decodes to an empty map, which reads as "this player has finished nothing"
  everywhere. One completion record travels as `last,period,total`; a comma collides with neither
  reserved character, so nothing about the wire contract or the inherited id hygiene moved.
- **`clearQuest` re-arms and KEEPS the completion record.** Abandon and the off-cooldown reset both
  go through it, and a lifetime cap either of them wiped could never be reached. The wipe is an
  explicit `setQuestCompletions(id, CompletionRecord.NONE)`.
- `markDirty` / `flush` stay the interface no-ops: the component's own codec persists a live
  component at tick end.

## The producers

All four register unconditionally and all four start with
`if (!ProgressionRuntime.defaultProducesKind(KIND)) return;` - a per-domain stand-down rather than a
global switch. They never touch an engine - everything goes through
[`producer/ProgressDispatch`](producer/ProgressDispatch.java), which builds the subject, feeds both
engines, and swallows nothing silently. World-thread throughout, which is where an ECS system
already is.

| Producer | Kind | Target | Amount |
|---|---|---|---|
| `ZigBlockBreakProducer` | `BREAK_BLOCK` | the broken block's id | 1 |
| `ZigMobKillProducer` | `KILL_ENTITY` | the dead entity's id | 1 |
| `ZigCraftProducer` | `CRAFT_ITEM` | the crafted OUTPUT item's id | the batch size |
| `ZigPickupProducer` | `PICKUP_ITEM` | the picked-up item's id | 1 |

- **`STAT_THRESHOLD` has no producer and never will.** It names a STATE rather than a moment, so
  nothing may fire for it; both engines read it themselves through the optional `factors` /
  `factorContext` pair, wired here to the portable `hytale:` standard library. That pair is the sole
  reason for the `zc-entity` edge.
- **The craft producer's query is deliberately unfiltered** (`Archetype.empty()`): narrowing to
  `PlayerRef` would drop workstation-emitted craft events entirely rather than merely failing to
  attribute them.
- **KNOWN GAP: a workstation craft does not count here.** `CraftRecipeEvent.Post` carries a recipe
  and a quantity and nothing else - verified against the official server source - so there is no
  honest way to name the crafter when the ECS subject is the workstation. The producer reads the
  subject's own `PlayerRef`, which covers an inventory craft and misses a bench one. Fix it the day
  the engine exposes the crafter, never by reflection.
- The craft producer matches the OUTPUT ITEM id rather than the recipe id, because that is what an
  author writes a "craft ten planks" objective against.

## The book

[`book/ObjectiveBookPage`](book/ObjectiveBookPage.java) is a `ToastablePage`: a Quests tab and an
Achievements tab over THE shared runtime's merged catalogue, whoever authored each entry, with the
minimal lifecycle affordances a page with no character in front of it can offer (accept an offered
quest, collect a finished one, hand in a step). `book/ObjectiveBookOpenInteraction` (`Type` name `ZigOpenObjectiveBook`) is what opens it,
registered from the wiring root; the shipped `Server/Item/Items/Consumables/Ziggfreed_Objective_Book.json`
chains it off a short Charging hold. Nothing hands the item out: it is a `/give` or an authored
reward, by design.

- **It is not a second opinion about anybody's progress.** The engines, the SUBJECT and the display
  text all come from the runtime. The subject matters most: one built locally reads neutral through
  another mod's store and drops every write, so an accept or a claim would silently do nothing.
- **Every mutating call is wrapped in the registered `ProgressionCallScope`**, which is what makes a
  claim from this page fire exactly what the owning mod's own menu would - its toast, its follow-on
  grants, its bookkeeping.
- **It walks the ENGINE, not a pool**, and names each row through `ProgressionTextSource` (first
  non-null wins). A surface reading one mod's catalogue would render the rest of the merged list
  blank.
- **`canDeliverTurnInAt(subject, quest, null)` is ALWAYS false**, so the hand-in button must not be
  gated on it (`readyToTurnInAt` refuses a null or blank place immediately). The book uses
  `firstActiveTurnIn(subject, quest, null)`, the documented "somewhere unlocked" form, then
  `attemptTurnIn`, which re-checks everything itself. The consequence is deliberate: a hand-in
  locked to a character can never be completed from the book.
- **Self-heal runs on every open**, before anything is read: it is what settles a standing-value
  step and what re-offers a repeatable whose cooldown has elapsed, so the list is never one open
  behind.
- **Achievement rows sort by CATEGORY inside each lifecycle section and carry a HEADER per run**,
  in the order the folded taxonomy declares, so a long list reads as combat things together and
  gathering things together rather than alphabetically. A header's label is a three-rung ladder
  (`AchievementGrouping.label`): an authored `TitleKey` first, because somebody said in so many
  words what the group is called; else the `achievement.category.<id>` convention key the schema
  points an author at; else the category id humanized (`boss_fights` reads as `Boss Fights`), since
  an untranslated word a player can read beats a raw key they cannot. That last rung is what lets a
  category ANOTHER mod folded and nothing describes still head its own run. Rank follows the same
  shape: a described category reads where its file says, an undescribed one after every described
  one, and content with NO category at all lands in one uncategorised bucket that reads last, with
  a line of the page's own. A header is budgeted together with the row it heads, so a list cut
  short by the row cap never ends on a heading with nothing under it.
- **A quest waiting out a cooldown is drawn in the locked section**, with the line its refusal names.
  Leaving it out makes a daily disappear between runs, which reads as content having been taken away
  rather than as a wait.
- **Only THREE accept refusals have a line of their own**, and that is a rule rather than an
  oversight: those three are the ones a player can act on. A key nothing can usefully say is a key
  nine translators maintain for nobody. Anything else - a spent calendar window, a spent lifetime
  cap, a gate evaluator's own reason - reads as the generic line rather than leaking a token at a
  player.
- **An authored key's `TextArgs` are passed to the message, never dropped.** A ladder is usually one
  translated line per rung with the number coming from `TextArgs`, so resolving the key without them
  empties that slot everywhere at once while the content file still reads as correct. That expansion
  lives in the TEXT SOURCE now (`ProgressionDefaults.AssetText`), because that is the layer that
  still has the catalogue the args were authored in; `@amount` is answered with the first step's
  amount, grouped, as a RAW value, since a digit needs no translating.
- **STATELESS across events.** Every binding round-trips the full next state and `handleDataEvent`
  reopens the page with it; EVERY exit path sends a response, or the client spins forever.
- **`.ui` contract**: `Pages/ZigObjectiveBookPage.ui` plus the appended `Pages/ZigObjectiveRow.ui`.
  A row's four step slots (`#StepRow0..3` / `#Step0..3` / `#StepProgress0..3`) are FIXED because an
  update can restyle an element that exists but never add one, so `MAX_STEPS` in Java MUST match the
  slot count; anything past it is summarised by `#StepMore`. All text is pushed on `.TextSpans`
  (a `.Text` sink neither substitutes `{0}` nor renders markup), and both tabs plus the row action
  are `Button` + inner `#Label` driven by `ZigRichButton`, never a `TextButton`.
- **Keys** live in `Server/Languages/<locale>/ziggfreedcommon.progression.lang` (in-file keys drop
  the `progression.` segment the filename carries) and the item's name/description in the sibling
  `items.lang`. All nine locales; see [`i18n/CLAUDE.md`](../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/i18n/CLAUDE.md)
  for why the filename is the prefix and why a key may live in exactly one file.

## The NPC quest page

[`questlist/ZigNpcQuestPage`](questlist/ZigNpcQuestPage.java) is the other surface over the same one
runtime: the book is what a player reads with nobody in front of them, this is what they read AT a
character. Two panels, the list on the left and the one quest being read on the right, with every
lifecycle affordance a character can offer - accept, hand in here, collect, abandon, pin.

- **The HERE list is three questions, asked of two authorities.** What a character HANDS OUT is an
  authoring-layer association no runtime can read, so it comes from
  [`quest/NpcOfferProviders`](../../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/quest/CLAUDE.md)
  asked over the character's whole ANSWER SET. The other two are pure quest state, so the engine
  answers both itself: what points BACK here (`readyToTurnInAt`) and what was TAKEN here
  (`acceptSiteOf`, compared case-insensitively against each id the character answers to). **"Given
  here" is ENGINE DATA - a consumer registers nothing for it**, which is what keeps a quest on its
  giver's tab while it is being carried even though the offer table has stopped offering it. The rule
  covers finished-but-uncollected quests as well as active ones, deliberately: a quest parked for
  collection at the character it was taken from has to be reachable there, or nobody could collect it.
  The MINE list is `activeAndUnclaimed` and nothing else.
- **A finished quest whose rewards belong elsewhere reads as PARKED, not Ready.** A quest may name
  where it is collected, and the engine refuses a claim made anywhere else - so the page asks
  `canCompleteAt` once per id the character answers to (the engine compares ONE id by design, which is
  what keeps an identity registry out of the progression module) and shows the status line instead of
  a button that would refuse. The claim, when it is offered, is made AT the id that answered.
- **Accept, hand in and collect all thread the SITE.** Accepting records where the quest was taken
  from, which is what a come-back-to-me quest and the given-here bucketing above both read; handing in
  names the id the step answered under, so the hand-in that finishes a quest at its own collection
  site pays out there and then rather than parking it. Passing the site always is deliberate: the
  content decides whether it matters.
- **The routed hand-in is a HIGHLIGHT, and a highlight is a pinned first row.** A ready quest never
  hijacks a conversation on its own, so whatever surfaces one routes here naming it. There is no
  scroll-to on a page, so pinning that row to the top and opening the detail panel on it IS "take me
  to it". `NpcQuestPages.open(npcId, questId, ...)` is the routed form; the five-argument form is the
  plain one.
- **It keeps instance state and reopens as `this`, unlike the book.** That is what a scroll-preserving
  `sendUpdate` needs: an update runs against the DOM the last full `build` produced, so a row index
  must be one that build RECORDED. `builtRowOrder` carries the exact order INCLUDING a marker per
  section heading, because a recomputed index ignores headings, can land on one, and an unresolved
  `#StatusDot` selector disconnects the player. Every path falls back to a full reopen when the
  recorded index is gone.
- **The five action buttons are bound once per build with no quest id in the binding**, and act on
  whatever the detail panel shows. A page update can restyle an element that exists but can never add
  or change a binding, so a partial swap of the panel needs no rebuild.
- **Rewards are read generically, and a reward nothing can name is DROPPED.**
  [`questlist/NpcQuestRewardChips`](questlist/NpcQuestRewardChips.java) reads a spec's own
  `NameKey`/`Icon`, then the kind FILE's `Presentation`, then the item form (an item's own engine
  display name), in the same order the deferred-payout layer reads them, so one reward cannot read
  differently on two screens. Nothing branches on a kind id. Painting a raw kind token at a player
  reads as a promise of something called that, so the honest answer to "nothing names this" is one
  fewer chip and a two-line `Presentation` on the kind file.
- **Only THREE accept refusals have a line of their own**, the same three the book keys and for the
  same reason: those are the three a player can act on. The rest read as the generic line rather than
  leaking a gate's internal token.
- **[`questlist/NpcQuestPageDeps`](questlist/NpcQuestPageDeps.java) is everything a consumer may say**
  and nothing it must: a character's NAME, its ANSWER SET, a THEME, a per-reward chip override, what
  FOLLOWS a quest settled here, and the completion TOAST. Every default leaves a working page, so a
  bare server needs no consumer at all. The hand-off is a seam rather than a call because the routing
  policy lives in the dialogue layer, which sits BESIDE this module in the graph rather than under it.
- **The detail panel's narrative comes from the shared text seam, per lifecycle state.**
  `ProgressionTextSource.lore(contentId, state)` is asked with `incomplete` / `active` / `complete`
  and the flavor line is the fallback, so content carrying per-state paragraphs reads with them and
  content carrying none is unaffected. It is a DEFAULT-bodied seam addition rather than a schema leaf:
  the words already exist under the consumer's own convention keys.
- **Registration is in the wiring root, and it is an OBJECT rather than a method reference** - the
  hazard is written out in full in the [module router](../../../../../../CLAUDE.md), because the
  method-reference form compiles and silently drops every highlight. Both `NpcQuestPages.open`
  overloads match the host interface's two shapes byte-exactly; the root supplies an implementation
  overriding both. A consumer that wants a different screen registers its own host and outranks
  nothing - first host to take the screen wins.
- **`.ui` contract**: `Pages/ZigNpcQuestPage.ui` plus the appended `Pages/ZigNpcQuestRow.ui` (ONE
  template for both a quest row and a section heading, since a list mixing two templates would give
  two different child sets at one index) and `Pages/ZigNpcQuestLine.ui` (ONE template for a step, a
  reward and a refusal). All text on `.TextSpans`; every labeled button is `Button` + `#Label` driven
  by `ZigRichButton`.
- **Keys** live beside the book's in `ziggfreedcommon.progression.lang` under `npcquests.`, and the
  page deliberately REUSES the book's `book.progress` / `book.action.*` / `book.quests.lock.*` /
  `book.toast.*` lines rather than minting a second wording for the same sentence.

## Tests

Pure decision cores and author-owned fixtures only, matching the rest of the library.
`ProgressionRuntimeTest` (in `zc-progression`) pins the registration surface - rank precedence, the
consumer-versus-consumer refusal, gate composition, the per-kind stand-down, layer merging;
`ProgressBlobTest` pins the wire form, the reserved characters inside an encoded value, and that a
corrupted entry costs that entry rather than the login; `ZigProgressComponentTest` pins the persisted
state machine, the completion record surviving a re-arm, and the deep `clone`; `ProgressDispatchTest`
drives a real quest engine and a real achievement engine over in-memory stores and proves one fired
moment reaches both. The engine-touching halves - the ECS producers themselves, the component
attach, the asset fold - land behind in-game smoke.

The three seams above are pinned by their own failure mode, since each one reports success while
delivering nothing. `DefaultPartsRewardGrantTest` walks a real collect through the real payout pass
and proves a handle standing in for the player is paid while one answering only for itself is not;
`DefaultPartsHandInTest` runs the book's own press sequence against an engine with the inventory
seams and against one without; `ProgressHandleFacetTest` pins the handle's declaration and what it
stands in for. `Subject.HandleFacets` itself is pinned in `zc-core` by `SubjectTest`, over the test's own
types, because the point is the dispatch rather than any player representation.

The NPC quest page is split the same way. `NpcQuestSectionsTest` pins the ordering rules a player
notices immediately and a refactor breaks silently - which bucket a status lands in, a finished quest
belonging elsewhere reading as parked rather than offering a button the engine refuses, a repeatable
waiting out its clock reading as locked rather than vanishing, a routed highlight beating a stale
selection while a surviving selection beats the first row. `NpcQuestRewardChipsTest` pins how a reward
READS from strings alone, over kind ids of the test's OWN invention, since naming a real consumer's
kind would disprove the thing being proved. `NpcQuestPageDepsTest` pins that an unfilled seam leaves a
working page and that a filled one throwing costs its own contribution rather than the screen. The
rendering itself, the offer providers and the engine calls behind each button are in-game smoke
territory.

`ObjectiveBookTextArgsTest` is a SOURCE check over the TEXT SOURCE, and deliberately: reaching a
rendering would initialize a page whose engine base builds a logger in a static initializer that
refuses to load in a JVM whose log manager is already up. `zc-dialogue`'s page render guard is written the same way
for the same reason. Behaviour that CAN be exercised belongs beside the thing it exercises: the
`TextArgs` expansion itself is pinned in `zc-progression`, next to the shared schema.
