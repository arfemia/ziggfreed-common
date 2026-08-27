# objectives/ - the library's own parts of the shared runtime (module `zc-objectives`)

Router for `com.ziggfreed.common.objectives`. What a BARE server gets: persistence for the shared
quest and achievement runtime, generic native-event producers feeding it, the asset content folded
into it, an ordinary item that reads it in game, the `/zigprogress` admin family that drives it, and the
in-world tracked-quest HUD that repaints off the quest engine's own events.

Module edges: `zc-core`, `zc-loot`, `zc-progression`, `zc-presentation`, `zc-cast`, `zc-entity`,
`zc-dialogue` (NPC identity, for the page at a character; `DialogueMemories`, for the admin verbs that
forget them) - all one-way `implementation`. Package
root `com.ziggfreed.common.objectives`.

**Why this module exists at all.** The book needs BOTH the engines and a page, and the tracked-quest
HUD needs BOTH the quest events and the HUD base. `zc-progression` may never import presentation
(its own router states the rule), and pushing the engines under presentation would drag them onto
every page consumer in the library. A module sitting ABOVE both adds no reverse edge, which is the
only shape that contradicts nothing.

## The one runtime: the book, the producers and every consumer read the same pair

There is ONE `QuestEngine` + `AchievementEngine` pair per server, held by
[`progress/runtime/`](../../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/progress/runtime/CLAUDE.md).
[`runtime/ProgressionDefaults`](runtime/ProgressionDefaults.java) registers THIS library's parts
into it - store, subjects, factor vocabulary, asset gate, both halves of a hand-in, the text a
surface names content by - all at LIBRARY-DEFAULT rank, so a consumer that brings its own is silently
in charge. Nothing here decides whether to run.

**Registration AND dispatching are unconditional.** The progress component TYPE and the five
producer systems are registered at `setup()`, because a component type registered after a world has
loaded cannot be read off entities saved carrying it and an ECS system is a setup-time registration.
The connect-time ATTACH is unconditional too, even where a consumer owns the stores: the component
also carries what conversations remember, so it has to exist either way. `usesDefaultStores()` gates
only the player-ready maintenance pass (the self-heal and auto-accept sweep), never the attach.

**There is no claim and no stand-down.** A consumer never registers a competing producer for a
native event covered here, so a moment is dispatched exactly once and double-counting cannot
happen. **The producer surface is OPEN and ADDITIVE**: a mod with a NET NEW moment registers the
kind through `ObjectiveKindRegistry` if it is not already known and calls `ProgressDispatch.fire`
from its own ECS event system. No registrar call, no claim, nothing to resolve. **And a consumer
REACTS to a produced moment rather than re-detecting it**: XP, a lifetime counter, a bonus drop hang
off `progress/runtime/MomentListener` (registered through `ProgressionRegistrar.momentListener`),
never off a second ECS system on the same native event beside the producer.

**What `ProgressDispatch` carries that a producer never has to think about.** Three things travel
with every fired moment, and each one is a silent-failure mode if it is dropped: the SUBJECT each
engine's own store understands (`questSubject` / `achievementSubject`, not one for both); the ZONE,
resolved through `progress/ZoneLocator` off the engine's `WorldMapTracker`, without which a
zone-scoped objective can never be satisfied; and the registered CALL SCOPE around each engine call,
without which a consumer's own listeners fire with no context and a completion pays out in silence.
A fourth thing is ASKED rather than carried: every registered `ProgressionSystemGate`, per half, so
an owner who has switched their quest or achievement system off for a player still has it off. None
of that is a producer veto: the producer always runs and always reaches the dispatch. What a refusal
costs is exactly the half it names, unwritten to that engine for that player; the other half is
untouched. There is one quest engine and one achievement engine per server, so today that IS the
whole of the refused half.

**The moment goes to every REACTION first, then to the engines.** `ProgressDispatch.fire` builds one
`progress/runtime/Moment` (kind, target, qualifier, amount, zone, store, ref, the producer's command
buffer, both subjects as resolved - either may be null - and the producer's typed `MomentPayload`)
and hands it to the composed `MomentListener` fan-out BEFORE the one early return and BEFORE both
system gates, unconditionally: a player with no subject on either side and a server with both
systems switched off still get every reaction. That placement is the whole point - a reaction is a
consumer's own product, not a progression half - and it is what makes the listener NOT the tap: the
tap says "an engine considered this" (fires inside each engine, after its subject and its gate, once
per action); the listener says "this happened". Nothing a listener does can refuse a moment. The
payload records live beside the producers (`BlockBreakPayload(event)`, `PickupPayload(event)`,
`CraftPayload(event, recipeId)`, `MobKillPayload(victimRef, death)`, `PlaceBlockPayload(event)`);
`MomentPayload` is an OPEN marker, never sealed, so a fourth-party producer ships its own record on
equal terms. The 6-arg `fire` stays the stable net-new entry point (no buffer, no payload); the
producers use the payload overload; and `fire(..., DispatchOptions)` is the ALIAS route - the same
action re-dispatched under a second kind or id, ENGINES ONLY, never re-entering the fan-out (a
reaction that already saw the action would otherwise pay twice). `ProgressDispatchTest` pins all of
it: null-subject and gate-refused moments reach the listener, a throwing listener costs only itself,
order is not a precedence, late registration fires, and the alias route reaches no listener.

**There is deliberately no "is anything listening?" short-circuit in the dispatch.** Both engines can
answer that cheaply (`index().forKind(kind)`), but the observer tap is fed on a dispatch that matched
NOTHING on purpose - a lifetime counter has to count a block broken while no content wanted it - and
the moment listeners are fed on every moment for the same reason, so a skip here would take exactly
those events away from every registered tap and every reaction. The cheap skip lives inside each
engine, after the tap has been fed.

**A kill nothing player-shaped landed is ASKED about before it credits nobody.** A turret, a summon
or a pet carries itself as the damage source, so the attacker has no `PlayerRef`; `ZigMobKillProducer`
asks the composed `progress/runtime/KillAttribution` (registered through
`ProgressionRegistrar.killAttribution`; every one asked in order, first real answer wins, a throwing
one skipped with a warn) which player it acts for, checks the answer really is a player, and fires
the moment for THAT player with the same payload. Nothing registered, no answer, and the kill
credits nobody, which is what a bare server has always done. `KillAttributionProducerTest` pins the
seam.

**The victim is asked about ONCE for a qualifier, at fire time.** `ZigMobKillProducer` asks the
composed `progress/runtime/KillQualifier` (registered through `ProgressionRegistrar.killQualifier`;
same shape as the attribution: asked in order, first real answer wins, a throwing one skipped with
a warn) what the killed entity carries - e.g. a difficulty tier a companion mod attributes - and
stamps the answer into the ONE primary `KILL_ENTITY` dispatch. A criterion authoring that qualifier
matches; an unqualified criterion keeps matching every kill, because the matching rule reads an
empty AUTHORED qualifier as "any" - which is also why there is deliberately NO second qualified
re-fire (it would count one kill twice for every unqualified criterion). No answer fires the kill
unqualified, byte-identical to a bare server. `KillQualifierProducerTest` pins the seam.

## The pieces

| Package | What it is |
|---|---|
| `runtime/` | `ProgressionDefaults`: the default registrations, the asset fold + its audit, the text source, the player lifecycle, and the `onProgressDirty` / `onProgressFlush` persistence contributions |
| `store/` | `ZigProgressComponent` (the persisted state) + `ProgressBlob` (the packing) + `ProgressHandle`/`ProgressSubjects` (the subject) + `ZigQuestStore`/`ZigAchievementStore` (the two adapters) |
| `producer/` | `ProgressDispatch` plus the six generic producers (block break, mob kill, craft, pickup, place block, and the instance-round listener off zc-instance's `InstanceRoundCompletedEvent`) and their six typed `MomentPayload` records |
| `book/` | the in-game two-tab surface and the item that opens it |
| `questlist/` | the NPC quest page: what one CHARACTER has to offer, list and detail |
| `command/` | `/zigprogress`: the admin family over THE runtime - quest, achievement and memory groups; see [its router](command/CLAUDE.md) |
| `admin/` | the progression admin page: `SystemSwitch` + `SystemSwitches` (the registered server-wide system switches) and `ProgressionAdminPage`/`Pages`/`Deps` (audience DEFAULT DENY, opened only by direct static call) - see below |
| `hud/` | the tracked-quest HUD (`TrackedQuestHud` + `TrackedQuestHuds` + `TrackedQuestHudDeps` + `TrackedQuestSnapshot` + `RepaintCoalescer`) and the tracked-quests side-panel renderer a page embeds (`TrackedQuestPanelRenderer`) |

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
- **A `PlayerRef` off that same handle.** A reward asked to run its command with the PLAYER's own
  authority looks the subject up as a `PlayerRef`, so `ProgressHandle` stands in for that too.
  Nothing extra answers a `Requires` block's `Permission` leaf: it is a `hytale:permission` factor
  bound, so the portable vocabulary registered above and a context carrying the player are all of it.

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
- **Twelve packed string leaves, not twelve map codecs.** Each map travels through
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
  everywhere. One completion record travels as `last,period,total,claimed`; a comma collides with
  neither reserved character, so nothing about the wire contract or the inherited id hygiene moved.
  A value with only THREE fields was saved before the collected tally existed and reads back with
  claimed equal to total, because those finishes were paid out under the rule they were written
  under - through the named `CompletionRecord.withoutCollectedTally` factory, which is the only way
  to say that and exists so no writer can say it by accident. Any other field count is unreadable
  and costs that entry alone.
- **`clearQuest` re-arms and KEEPS the completion record.** Abandon and the off-cooldown reset both
  go through it, and a lifetime cap either of them wiped could never be reached. The wipe is an
  explicit `setQuestCompletions(id, CompletionRecord.NONE)`.
- **`markDirty` / `flush` write nothing and FAN OUT.** The component's own codec persists a live
  component at tick end, so neither adapter has anything of its own to do; both instead call
  `ProgressionDefaults.fireProgressDirty` / `fireProgressFlush`, which walk every consumer registered
  through `ProgressionDefaults.onProgressDirty(Consumer<Subject>)` /
  `onProgressFlush(Consumer<Subject>)`. **Both are CONTRIBUTIONS**: registering one never displaces
  another, every listener is asked, a listener that throws is warned about by name and the rest still
  run, and `ProgressionDefaults.reset()` clears both. A null listener is refused at REGISTRATION, so
  a consumer that offers one is named at its own setup rather than inside somebody else's hand-in.
  Dirty and flush are kept apart on purpose - dirty says "this player changed", flush says "commit
  it before a crash costs them what they just earned", and a batching backend needs to hear those
  separately.
- **What the dirty fan-out COVERS, exactly.** A backend that only ever hears about some of the
  writes is worse than one that hears about none, because the gap is invisible until a player's
  state reverts on the next hydrate. Three sources land on this one component and all three report:
  every write the two adapters make for the quest and achievement engines (pins and unpins
  included - `QuestEngine.track` / `untrack` / `pruneStaleTracked` and `AchievementEngine.pin` /
  `unpin` / `prunePins` all mark dirty, so a tracker a player tidied stays tidy), and every dialogue
  memory `ZigProgressDialogueStore` remembers or forgets, which fans out from the view itself
  because the memories ride this same component. Each ENGINE method that writes reports its OWN
  write, the public ones an authoring layer calls included (`QuestEngine.clearQuest`,
  `markCompleted`, `markUnclaimed`), so a re-arm from a rotating offer or a chained quest's pool is
  not a silent one.
- **The two doors that do NOT report, and why.** Both are a caller going AROUND the engine. One is a
  caller holding the component and writing it directly: `ZigProgressComponent.claimMigration` is the
  only instance today, and whoever claims a migration marks the player dirty itself, since it is the
  only side that knows a claim was made. The other is a caller reaching the store ADAPTER itself
  (`store().clearQuest`, `setStatus`, `putProgress`) rather than the engine call that owns that
  write, which the forwarding stores in `progress/runtime/ProgressionParts` make reachable from
  anywhere. Neither is reported for, and both carry the engine's own obligation: whoever makes the
  write says so. Add a further source and it reports here too, or it silently does not survive a
  restart on a consumer's backend.
- **What FLUSH means here, and the FIVE points it means it at.** Dirty is every change; flush is the
  narrow subset where a player-owned transaction closed, so a crash in the next second cannot cost
  them what they just collected. The whole list, because a backend sizes its write budget on it:
  a quest COLLECTED (`QuestEngine.claim`), an achievement COLLECTED (`AchievementEngine.claim`) and a
  points milestone COLLECTED (`claimMilestone`) - all three unconditional, the player pressed the
  button - plus a quest that paid out the instant it finished (`checkCompletion`'s auto-claim path)
  and an administrator's close-out (`forceComplete`), those two ONLY when a reward was actually
  delivered.
- **What does NOT flush, which is the load-bearing half.** Earning an achievement, reaching a points
  milestone, and any payout that delivered nothing report dirty and wait for the batch. Earning is
  something the engine DECIDES rather than something a player asked for, and it arrives in bulk: a
  self-heal walks the whole catalogue on login, one earn cascades through a chain of metas, and each
  earn re-checks the milestones - so committing at any of those turns one login into a write per
  achievement the player already had. **Nothing inside a self-heal, a re-arm, a prune or a pin sweep
  commits, and no single engine call commits twice.** A quest that finishes and PARKS has paid
  nothing yet, so it waits, and commits when the player comes to collect it. Adding a sixth flush
  point means arguing it past that paragraph first.
- **The component comes from a bare `ZigProgressComponent` handle FIRST**, and from a
  `ProgressHandle` only as the fallback. `ProgressHandle` itself answers for the component through
  `Subject.HandleFacets`, so both routes end in the same place. This is the seam that lets a consumer
  supplying its OWN `ProgressionSubjectSource` keep these two adapters as THE store: its handle
  offers the component and nothing else has to change. **A consumer must never bring a second store**
  (runtime router rule: two stores is two versions of one player's state), and these two seams
  together are why it never needs to.
- **The wire format is GOLDEN-PINNED.** `store/ZigProgressBlobCompatTest` decodes a checked-in blob
  at `src/test/resources/fixtures/zig-progress-blob-1-6-0.bin` covering all twelve leaves, built by
  `ZigProgressBlobFixture` and written once by the gated `ZigProgressBlobFixtureGenerator`
  (`-DexportProgressFixture=true`, forwarded by `gradle/zc-module.gradle`). **The file is NEVER
  regenerated**: a codec change has to keep decoding it, because a consumer's database backend
  stores this component in exactly that form. Byte-equality is deliberately not asserted (the maps
  are `ConcurrentHashMap`s and iteration order is not a contract); what is pinned is that old bytes
  decode to the same state, plus a lossless decode-encode-decode loop.

## The producers

All six register unconditionally and all six fire unconditionally; nothing stands one down. The
two that credit a WORLD action first ask zc-world's `world/placed/PlacedBlockLedger` and skip a
block or an item the player put down themselves - and since a consumer's XP is a REACTION to the
moment they fire, a placed block produces no moment and nothing reacts, so progress and XP can never
disagree about it. They never touch an engine - everything goes through
[`producer/ProgressDispatch`](producer/ProgressDispatch.java), which builds the subject, fans the
moment to every reaction, feeds both engines, and swallows nothing silently. World-thread
throughout, which is where an ECS system already is; the one producer that is a BUS listener rather
than an ECS system (`ZigInstanceRoundProducer`, off zc-instance's `InstanceRoundCompletedEvent`)
resolves each named player's own live world and hops there when it is not already on it. Each hands
its own typed payload record to the dispatch, so a reaction reaches what the tuple cannot carry.

| Producer | Kind | Target | Amount | Payload |
|---|---|---|---|---|
| `ZigBlockBreakProducer` | `BREAK_BLOCK` | the broken block's id | 1 | `BlockBreakPayload(event)` |
| `ZigMobKillProducer` | `KILL_ENTITY` | the dead entity's id (qualifier: the composed `KillQualifier`'s answer for the victim, null = unqualified) | 1 | `MobKillPayload(victimRef, death)` |
| `ZigCraftProducer` | `CRAFT_ITEM` | the crafted OUTPUT item's id | the batch size (`craftBatchAmount`, at least 1) | `CraftPayload(event, recipeId)` |
| `ZigPickupProducer` | `PICKUP_ITEM` | the picked-up item's id | 1 | `PickupPayload(event)` |
| `ZigPlaceBlockProducer` | `PLACE_BLOCK` | the placed item's id | 1 | `PlaceBlockPayload(event)` |
| `ZigInstanceRoundProducer` | `INSTANCE_ROUND_ENDED` per participant, `INSTANCE_ROUND_WON` per winner | `<modId>:<modeId>` (the `<modId>:` prefix matches any mode), qualifier the preset id | 1 | `InstanceRoundPayload(event)` |

- **The place-block producer counts exactly what the placement recorder records**, through the
  recorder's own `PlacedBlockRecorder.placementCounts` predicate (a cancelled placement never
  happened, an empty or blank item is nothing, a creative-mode placement is exempt), so what is
  remembered as "placed" and what is produced as a moment can never drift. `ZigPlaceBlockProducerTest`
  pins the three filters.
- **The kill producer's credited player may not be the raw attacker**: for a non-player attacker it
  asks the composed `KillAttribution` and fires for the owner it names (see above); the payload's
  death still names the raw attacker, so a reaction can tell the two apart.
- **The kill producer's qualifier is the composed `KillQualifier`'s answer, asked ONCE at fire
  time** (see above): a registered contribution's tier rides the one primary dispatch, and no
  answer fires the kill unqualified, byte-identical to a bare server.

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

[`book/ObjectiveBookPage`](book/ObjectiveBookPage.java) is the full-screen two-tab progression
menu over THE shared runtime's merged catalogue, whoever authored each entry: the QUESTS tab is
the whole quest log, the ACHIEVEMENTS tab a two-panel browser. `ObjectiveBookPage` is the host
(frame, tab strip, consumer chrome, every verb and partial update); [`book/BookQuestsTab`](book/BookQuestsTab.java)
and [`book/BookAchievementsTab`](book/BookAchievementsTab.java) paint the two surfaces.
`book/ObjectiveBookOpenInteraction` (`Type` name `ZigOpenObjectiveBook`) opens it, registered from
the wiring root; the shipped `Server/Item/Items/Consumables/Ziggfreed_Objective_Book.json` chains
it off a short Charging hold. Nothing hands the item out: it is a `/give` or an authored reward,
by design.

- **It is not a second opinion about anybody's progress.** The engines, the SUBJECT and the display
  text all come from the runtime. The subject matters most: one built locally reads neutral through
  another mod's store and drops every write, so an accept or a claim would silently do nothing.
- **Every mutating call is wrapped in the registered `ProgressionCallScope`**, which is what makes a
  claim from this page fire exactly what the owning mod's own menu would - its toast, its follow-on
  grants, its bookkeeping.
- **It walks the ENGINE, not a pool**, and names each row through `ProgressionTextSource` (first
  non-null wins). A surface reading one mod's catalogue would render the rest of the merged list
  blank.
- **The QUESTS tab** is category chips (a native `DropdownBox` once the rendered chips cannot fit
  the strip: the ONE shared `ObjectiveBookPage.categoryChipsFit` rule both tabs use counts every
  chip, the All chip included, at its template's fixed width against the page's per-open
  `stripWidthBudget()` - the frame minus whichever consumer columns painted - and the chip
  containers never wrap, so an overflow can only clip at the strip edge, never paint over the row
  below), a search field with a native placeholder, a tag dropdown collected BEFORE the tag filter
  is applied (so it never collapses to one entry) and hidden entirely when no visible quest
  carries a tag (an active tag filter keeps it, or it could not be cleared), status chips
  (active = carried or ready to collect; available = offered
  and takeable; completed = finished or waiting out a clock), then ONE scrolling region: the
  pre-expanded Active section above the browse list. Rows expand in place and carry inline
  objectives (order groups locked until earlier groups land, step headings between them), tag
  chips coloured by the shared `ui/TagColors` table, a requirements line for a quest not yet taken
  (the consumer's reading first, else the generic gate reading, else `quest.LockReasons.bestLine`, the shared zc-progression mapping every locked surface reads),
  reward chips through the shared `RewardChips` reading, a compact native per-row progress bar,
  and the accept / gold-claim / hand-in / abandon / track affordances. `#ActionBtn` is ONE
  state-dispatched button (accept, or a gold Claim), pre-bound even while hidden, so a completing
  hand-in morphs it in place with no re-bind.
- **The ACHIEVEMENTS tab** is two panels. LEFT: category tabs with unlocked/total counts (feat-only
  categories skipped; the same chips-fit rule falls them to the dropdown), subcategory chips
  (rendered only when the picked category has a second level), search, a sort DROPDOWN
  (default / A-Z / % progress - a single choice, so every label renders whole) and status chips
  over compact rows plus the earned-only feats section, each LADDER collapsed to the rung being
  climbed (pinned rungs and a live search bypass the collapse). RIGHT: the selected achievement's
  whole story - header (with a pin toggle of its own: bound once id-less like the claim button so
  it acts on the live selection, hidden for a feat, and repainted IN SYNC with the list row's pin
  glyph in the one partial update whichever of the two was clicked), meta line, description,
  native progress bar, criteria, what a capstone
  requires, what this feeds, the whole ladder, rewards with their auto / pending / claimed /
  locked tags and the ONE claim button - or, while nothing is selected, the OVERVIEW: recent
  unlocks, nearest-to-complete, pinned, the category completion grid, and the consumer's points
  milestones. Selecting a row repaints the right panel alone.
- **Scroll-preserving partial updates are the STANDARD** for accept, collect, hand-in, abandon,
  track, pin, expand and select: the pattern is clear the repainted hosts, re-append, bind ONLY
  the fresh elements in the partial update's own event builder, `sendUpdate(cmd, events, false)`.
  A full reopen happens only where a partial cannot tell the truth: filter / search / sort / tab
  changes, a cap or completion that re-ranks every row, and a board-managed quest dropping back to
  not-started (its row must never flash an Accept the board owns).
- **State model**: the FILTER state is stateless (every binding round-trips the full next state,
  the live search text captured via `@SearchInput` on every one, so typed-but-unsubmitted text
  survives any click); row expansion and the selected achievement are per-instance UI memory the
  reopened instance is threaded, the same way the NPC quest page keeps its selection. EVERY exit
  path sends a response, or the client spins forever.
- **[`book/ObjectiveBookDeps`](book/ObjectiveBookDeps.java) is everything a consumer may say** and
  nothing it must - every default leaves the book working on a bare server. The seams: the
  `PageTheme` (now varargs over the inner panels, `#LeftPanel` + `#SidePanel`), a RAIL painter
  over `#LeftPanel` (branding + navigation; the column hides when nothing paints it, and with it
  PAINTED the book's own Quests | Achievements strip hides too - the rail carries its own two tab
  entries, so the strip would be a second switcher; the rail's highlight is the one tab indicator,
  the header row reflows with the title growing into the space, and a bare server keeps the strip
  as its only switcher) and a SIDE
  painter over the achievements tab's third column, both binding their controls back through
  `Chrome.bindExt` to the one `ext` action channel the `ExtHandler` answers; board-managed-quest
  presentation (the predicate, the substitute pills, the at-the-board hint - a managed quest lists
  only while carried or ready to collect); the requirements line; tag labels; reward chips (the
  shared `RewardChips.Source` shape); server-first claims (who claimed, is it the viewer); the
  points-milestone ladder (`MilestoneView` list + a claim callback; an empty ladder hides the
  block and the third header stat); accept/abandon feedback hooks (a FILLED seam owns those
  announcements and the book's own accepted/abandoned toasts stand down, so one action is one
  toast); and a quest-claim pre-check
  (`QuestClaimPreCheck`, consulted before the engine's own claim call - a refusal Message stops the
  claim cold and answers the client as an error toast, never reaching `engine.claim`). Registered
  once via `ObjectiveBookPages.deps(Supplier)`; the narrow `theme(...)` registration keeps working
  and is what the default theme falls through to.
- **Status colours are the shared `ui/StatusTones`** (zc-presentation), the same six tones the NPC
  quest list's dots read, so "ready", "in progress" and "locked" are one colour everywhere.
- **`canDeliverTurnInAt(subject, quest, null)` is ALWAYS false**, so the hand-in button must not be
  gated on it (`readyToTurnInAt` refuses a null or blank place immediately). The book uses
  `firstActiveTurnIn(subject, quest, null)`, the documented "somewhere unlocked" form, then
  `attemptTurnIn`, which re-checks everything itself. The consequence is deliberate: a hand-in
  locked to a character can never be completed from the book.
- **Self-heal runs on every open**, before anything is read: it is what settles a standing-value
  step and what re-offers a repeatable whose cooldown has elapsed, so the list is never one open
  behind.
- **An authored key is emitted through zc-core's `ContentKeys`, never as written** (via the
  registered text sources and `AchievementGrouping.label`); a category header's label ladder and
  rank rules live in `book/AchievementGrouping`, unchanged: an authored `TitleKey`, else the
  `achievement.category.<id>` convention key, else the id humanized, with undescribed categories
  after described ones and the uncategorised bucket last, on a line of the page's own.
- **A String-only sink never shows a raw key**: everything the book flattens - an item slot's
  hover name, a dropdown entry's label, the search haystacks, the A-Z sort keys - goes through
  `UiText.flatten`, which resolves a translation through the server's default-language catalogue
  with its params substituted (zc-core's `PlainText`; an id with no value still degrades to the
  id, the traceable form). Display text everywhere else stays a client-resolved Message on
  `.TextSpans`; never flatten for a sink that can take a Message.
- **`.ui` contract**: `Pages/ZigObjectiveBookPage.ui` is the frame (the `Padding: (Full: 12)` on
  `#Content` is load-bearing: at 0 the `#LeftPanel`/`#SidePanel` bevels stack against the frame
  bevel and read as heavy shadow), plus the appended row family: `ZigQuestLogRow.ui` (a quest),
  `ZigBookObjectiveRow.ui` (one objective line, restyled for step headings), `ZigBookTagChip.ui`,
  `ZigBookCatTab.ui` / `ZigBookWideTab.ui` (filter chips, narrow and name+count), `ZigAchListRow.ui`
  (a compact achievement, reused by the overview's recent / nearest / pinned blocks),
  `ZigAchChipRow.ui` (a related-achievement chip), `ZigAchCriterionRow.ui`,
  `ZigAchCategoryCard.ui`, `ZigMilestoneCard.ui` and `ZigBookRewardRow.ui` (one reward line,
  shared by quest rows, the detail column and milestone cards). All text is pushed on `.TextSpans`
  (a `.Text` sink neither substitutes `{0}` nor renders markup; the expand toggle's bare glyph
  goes through `UiText.setText`), every labeled button is a `Button` + inner `#Label` driven by
  `ZigRichButton`, and the pin / track glyph is BAKED in the template (Java toggles the off/on
  variants and retints the pill, whose `ButtonStyle` is the shared `$Z.@ZigPinBtnStyle` on every
  face - the quest row's track button, the achievement row's pin, the detail header's `#DPinBtn`;
  a TexturePath pushed from Java renders a red X). A lang key
  referenced INLINE from a `.ui` file (`PlaceholderText: %...;`) must use camelCase segments: the
  parser's `%...;` token grammar rejects underscores, and one unparseable document disconnects
  every client at load (which is why the two placeholder keys are `searchPlaceholder`).
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
  [`quest/NpcOfferProviders`](../../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/quest/CLAUDE.md) (this module registers the DEFAULT provider, over the runtime catalogue and each quest's own giver id; it answers the cheap "anything for me" read separately, stopping at the first takeable quest, because that one is asked once per character on screen)
  asked over the character's whole ANSWER SET. The other two are pure quest state, so the engine
  answers both itself: what points BACK here (`readyToTurnInAt` - a hand-in handable here, or an
  outstanding step whose KIND declares a place-typed target naming this whole id, so a step tracking
  blocks or items points nowhere) and what was TAKEN here (`acceptSiteOf`, compared
  case-insensitively against each id the character answers to). **"Given
  here" is ENGINE DATA - a consumer registers nothing for it**, which is what keeps a quest on its
  giver's tab while it is being carried even though the offer table has stopped offering it. The rule
  covers finished-but-uncollected quests as well as active ones, deliberately: a quest parked for
  collection at the character it was taken from has to be reachable there, or nobody could collect it.
  The MINE list is `activeAndUnclaimed` and nothing else.
- **What the default provider costs, and why it is not indexed.** `RuntimeOffers` walks the whole
  runtime quest catalogue on every ask and filters on `Quest.npcViewId()`, so the per-ask cost is one
  null check plus one `equalsIgnoreCase` per quest on the server against the character's answer set
  (`quests()` is a live map view, so nothing is allocated to walk it). Building the panel then reads
  the requirement block TWICE for each not-started quest that reaches the list: once for `isOfferable`
  (is it listed at all) and once for `canAccept` (is it takeable now or shown locked). Neither has
  been optimised, deliberately. **An index by giver id would need an invalidation signal the engine
  does not publish** - a catalogue re-fold replaces `setQuests` wholesale with no generation to
  observe - and a giver index left stale is a character holding out quests that are no longer theirs,
  which is worse than the walk. **Collapsing the two reads means deciding offerability from the accept
  check's reason TOKENS**, which puts a second copy of the `isOfferable` rule in this file, to drift
  the first time that rule moves. The cheap read is already separate: `hasOffersAt` never calls
  `isOfferable` and stops at the first takeable quest, because that is the one asked once per
  character on screen; `offersAt` runs when a player has opened a panel. Fix the index the day the
  engine publishes a catalogue generation, and not before.
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
  to it". `NpcQuestPages.open(npcId, questId, store, ref, player)` is the routed form; the one
  without a quest id is the plain one. Both read the PLAYER's own reference off `player`, never off
  `ref`: `ref` is the ANCHOR the page is opened on, which at a press-F is the character's entity
  rather than the player's.
- **It keeps instance state and reopens as `this`, unlike the book.** That is what a scroll-preserving
  `sendUpdate` needs: an update runs against the DOM the last full `build` produced, so a row index
  must be one that build RECORDED. `builtRowOrder` carries the exact order INCLUDING a marker per
  section heading, because a recomputed index ignores headings, can land on one, and an unresolved
  `#StatusDot` selector disconnects the player. Every path falls back to a full reopen when the
  recorded index is gone.
- **The five action buttons are bound once per build with no quest id in the binding**, and act on
  whatever the detail panel shows. A page update can restyle an element that exists but can never add
  or change a binding, so a partial swap of the panel needs no rebuild.
- **Rewards are read generically, and a reward nothing can name is DROPPED.** The page reads chips
  through `zc-loot`'s shared `loot/reward/RewardChips` (the deps' `RewardChipSource` seam IS its
  `Source` shape): a spec's own `NameKey`/`Icon`, then the kind FILE's `Presentation`, then the item
  form (an item's own engine display name), in the same order the deferred-payout layer reads them,
  so one reward cannot read differently on two screens. Nothing branches on a kind id. Painting a
  raw kind token at a player reads as a promise of something called that, so the honest answer to
  "nothing names this" is one fewer chip and a two-line `Presentation` on the kind file.
- **Only THREE accept refusals have a line of their own**, the same three the book keys and for the
  same reason: those are the three a player can act on. The rest read as the generic line rather than
  leaking a gate's internal token.
- **[`questlist/NpcQuestPageDeps`](questlist/NpcQuestPageDeps.java) is everything a consumer may say**
  and nothing it must: a character's NAME, its ANSWER SET, a THEME, a per-reward chip override, what
  FOLLOWS a quest settled here, and the completion TOAST. Every default leaves a working page, so a
  bare server needs no consumer at all. **The two identity defaults are the ASSETS' own answer**
  (`ASSET_NAMES` reads `NpcNames::nameFor`, `ASSET_ANSWER_SETS` reads
  `NpcIdentities::answerSetForPrimary`), so the header, the nameplate over the character's head and a
  "Talk to X" objective cannot disagree, and a consumer fills either seam only to name a character
  the placement and identity assets do not describe. The hand-off is a seam rather than a call because
  that decision is the routing layer's policy, never this page's - and its DEFAULT
  (`ENGINE_HAND_OFF`) already routes through the dialogue engine's installed quest host via
  `QuestCompletionRouting.handOff`, read at click time, so the giver's closing conversation plays
  for every quest-bearing consumer without a fill; `NO_HAND_OFF` stays as the explicit opt-out.
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
- **`.ui` contract**: `Pages/ZigNpcQuestPage.ui` plus zc-presentation's shared appended
  `Pages/ZigSelectRow.ui` (ONE template for both a quest row and a section heading, since a list
  mixing two templates would give two different child sets at one index; the commerce pages append
  the same file, so a readability change lands on every list at once - the row button is `#RowBtn`)
  and `Pages/ZigDetailLine.ui` (ONE template for a step, a reward and a refusal). All text on
  `.TextSpans`; every labeled button is `Button` + `#Label` driven by `ZigRichButton`.
- **Keys** live beside the book's in `ziggfreedcommon.progression.lang` under `npcquests.`, and the
  page deliberately REUSES the book's `book.progress` / `book.action.*` / the shared `ziggfreedcommon.progress.lock.*` /
  `book.toast.*` lines rather than minting a second wording for the same sentence.

## The tracked-quest HUD

[`hud/TrackedQuestHud`](hud/TrackedQuestHud.java) is the in-world panel of a player's pinned quests,
drawn to match the native objective HUD (`Common/UI/Custom/Hud/ZigQuestTracker.ui` plus the three
native textures copied beside it, since a server-shipped document resolves a texture by name next to
itself). Attached to EVERY player at ready and dropped at disconnect by
[`hud/TrackedQuestHuds`](hud/TrackedQuestHuds.java), which `ProgressionDefaults.install` calls last;
it reads the runtime's own subject, so it shows the right list whoever owns the stores, and its attach
is deliberately NOT behind the `usesDefaultStores()` gate (it rides the ready event at LATE priority,
after the maintenance pass has hopped to the world thread, so the first paint shows what that pass did).

- **It repaints on the quest engine's native events, and there is no tick anywhere.** Six
  subscriptions - `QuestTracked` (the pin event, fired by the engine on a real change only),
  `QuestAccepted`, `QuestObjectiveProgressed`, `QuestCompleted`, `QuestClaimed`, `QuestAbandoned` -
  each look the player up by the uuid the event carries in `TrackedQuestHuds.LIVE`, a
  `ConcurrentHashMap` written at attach and cleared at detach, and ask that tracker to repaint. Every
  event handler is one map read plus one queue offer, safe from any thread.
- **A burst paints once.** [`hud/RepaintCoalescer`](hud/RepaintCoalescer.java) queues the paint on
  the player's own `World` (an `Executor`) and folds every request that arrives before it runs; the
  world drains its task queue inside the same tick, so a swing of the pickaxe with five gathering
  quests pinned is one paint at the end of that tick. It is not a poller: nothing runs when nothing
  was asked for. The objective event is additionally pre-filtered against what the tracker LAST
  SHOWED (`Tracker.shows`), so a quest the player is not watching costs no paint at all.
- **World thread, by construction.** A repaint may be ASKED for from anywhere; the paint itself
  runs where the coalescer queued it, on the player's world thread, and resolves the subject per
  paint off the live reference (a player crossing worlds gets a new one). Reading the tracked state
  resolves the player's entity, which is why the hop is not optional.
- **What it shows is a value first, commands second.** [`hud/TrackedQuestSnapshot`](hud/TrackedQuestSnapshot.java)
  is one paint worked out from the engine: hidden when nothing is pinned or the deps say so, one
  block per pinned quest (capped at the document's five), only the CURRENT step's rows (capped at
  four), the count blank on a report-back hand-in, `complete` flipping the glyph and the colours.
  Titles and lines come from the shared `progress.runtime.ProgressionTexts` walk (the registered
  text sources, else its own placeholder lines), so the HUD speaks NO key of its own. The drawing onto the document's fixed,
  positional slots is the mechanical half and is in-game smoke.
- **The consumer's part is [`hud/TrackedQuestHudDeps`](hud/TrackedQuestHudDeps.java)**, registered
  once through `TrackedQuestHuds.deps(Supplier)` and asked lazily on every paint: a `HudTheme` paint
  over the appended document (default nothing, which IS the native look; a theme retints through
  `ui/UiRetint` on the tracker's own selectors and must never be pointed at a page-frame painter,
  whose selectors this document does not carry), a `HudAudience` asked per subject on every repaint
  (default everyone), the position and the enabled flag as SUPPLIERS a consumer answers off its own
  layout file so its existing admin surfaces keep working, and the four native text colours as
  independent knobs. Every reader is guarded: a seam that throws costs its own answer, never the
  tracker.
- **Two moments no quest event announces**: a player hiding the HUD for themselves, and a rule of the
  world they walked into. Both are the CONSUMER's to know, and it pushes
  `TrackedQuestHuds.repaint(PlayerRef)` from those two sites. Owner-wide changes go through
  `repaintAllOnline()` (switched on or off) and `refreshPositionForAllOnline(HudPosition)` (moved).
- **The install line names the six subscriptions.** The engine dispatches an event only when
  something is listening, so a subscription that silently failed to register would be a tracker
  that silently never updates; the one INFO at install is the only place that shows.
- **[`hud/TrackedQuestPanelRenderer`](hud/TrackedQuestPanelRenderer.java) is NOT the HUD.** It is
  the shared renderer for a tracked-quests SIDE PANEL a page embeds - one `Pages/ZigTrackedQuestRow.ui`
  row per pinned quest inside the host page's `#TrackedQuestsList` plus its `#TrackedQuestsEmpty`
  note (`tracked.empty`) - reading the same runtime, so a page's panel and the HUD can never disagree.
  Every `maxTracked()` slot is addressed on every paint (appended on a full build, hidden when
  surplus) so a `sendUpdate` repaints by index; the host page's header label, if any, stays with the
  page.

## The admin page

[`admin/ProgressionAdminPage`](admin/ProgressionAdminPage.java) is the third surface, and the one
that is NOT for players: one row per registered [`admin/SystemSwitch`](admin/SystemSwitch.java) -
a consumer's server-wide progression-system switch (which `ProgressionSystem`, a pre-built
client-resolved label + optional hint, a server-wide `BooleanSupplier` read, an optional `Writer`,
a render order) - registered additively through
[`admin/SystemSwitches`](admin/SystemSwitches.java) over `zc-core`'s `RegistryLedger`.

- **The switch registry lives HERE, not in zc-progression**: a switch carries a `Message` label,
  and a Message is presentation, which zc-progression must stay free of. The runtime's
  `ProgressionSystemGate` stays the per-player DECISION; a `SystemSwitch` is typically what such a
  gate reads, and the page never asks the gates.
- **Three honest toggle paints.** On / Off for a value that read cleanly; a locked tint (and no
  binding) plus a "governed elsewhere" hint line for a switch with no writer; and "?" for a read
  that threw - never Off, the gate posture mirrored per side's own failure cost
  (`SystemSwitches.readGuarded` answers null on a throw, one warn per id; `writeGuarded` refuses
  with false on an absent or throwing writer, and the page answers the refusal as a toast).
  An EMPTY registry renders a page-level note, not rows.
- **Opened ONLY by the direct static `ProgressionAdminPages.open`**, never a registered
  destination - an admin screen must not be pack-addressable - and refused unless the registered
  [`admin/ProgressionAdminDeps`](admin/ProgressionAdminDeps.java) audience passes. The audience
  DEFAULTS TO DENY and a throwing audience denies too (fail-closed, the one deliberate exception to
  the every-default-leaves-a-working-page rule: the library cannot know what "is an admin" means).
  The deps also carry the ONE shared `NpcQuestPageDeps.PageTheme` and a Back handler (default:
  the page closes).
- **Rows are zc-presentation's shared `Pages/ZigFormToggleRow.ui`**, appended directly (not
  through `SettingsForm`, whose toggle knows only on/off and always binds); the page's own frame is
  `Pages/ZigProgressionAdminPage.ui`. Every line resolves from the admin family's own
  `ziggfreedcommon.progression.admin.lang` under `page.*`, beside the command family's keys.
- **Stateless across events**: the full state a binding round-trips is an action and a switch id;
  a toggle writes, toasts the result, and reopens so every row repaints from a live read.

## Tests

Pure decision cores and author-owned fixtures only, matching the rest of the library.
`ProgressionRuntimeTest` (in `zc-progression`) pins the registration surface - rank precedence, the
consumer-versus-consumer refusal, gate composition, layer merging;
`ProgressBlobTest` pins the wire form, the reserved characters inside an encoded value, and that a
corrupted entry costs that entry rather than the login; `ZigProgressComponentTest` pins the persisted
state machine, the completion record surviving a re-arm, and the deep `clone`; `ProgressDispatchTest`
drives a real quest engine and a real achievement engine over in-memory stores and proves one fired
moment reaches both, and that every registered reaction sees it first (a null-subject moment, a
gate-refused moment, a throwing listener costing only itself, order not a precedence, late
registration, and the alias route reaching NO listener). `PlacedGuardProducerTest` pins the
anti-exploit half - the SOLE home of that guarantee now that no consumer reads the ledger for a
break or a pickup: a placed-then-broken block and a placed-then-picked-up item both decline, while a
fresh one credits. `KillAttributionProducerTest` pins the attribution seam (a non-player attacker
with a registered attribution fires for the answered player, none registered credits nobody, first
real answer wins, a throwing one is skipped); `KillQualifierProducerTest` pins the qualifier seam
(the registered answer stamped into the one dispatch, none registered fires unqualified, an
unqualified criterion matching qualified and unqualified kills once each, a qualified criterion
matching only its own, a throwing one skipped); `ZigPlaceBlockProducerTest` pins the three placement
filters; `ZigCraftProducerTest` pins the batch-amount clamp. The engine-touching halves - the ECS
producers themselves, the component attach, the asset fold - land behind in-game smoke.

The three seams above are pinned by their own failure mode, since each one reports success while
delivering nothing. `DefaultPartsRewardGrantTest` walks a real collect through the real payout pass
and proves a handle standing in for the player is paid while one answering only for itself is not;
`DefaultPartsHandInTest` runs the book's own press sequence against an engine with the inventory
seams and against one without; `ProgressHandleFacetTest` pins the handle's declaration and all three
things it stands in for. `Subject.HandleFacets` itself is pinned in `zc-core` by `SubjectTest`, over
the test's own types, because the point is the dispatch rather than any player representation.

`ProgressStoreContributionTest` pins the persistence half: that the dirty and flush fan-outs stack
rather than displace, that a listener throwing an exception OR an error is guarded and costs only
its own notification, that a null listener is refused at registration, that `reset` clears both, and
that the two routes a subject can hand these adapters their component - a bare `ZigProgressComponent`
handle and a consumer handle answering for one - both read and write. It then drives a real
`QuestEngine` and a real `AchievementEngine` over the real default stores to pin the COVERAGE that is
easiest to lose in a refactor: a quest pin, an unpin, a stale-pin sweep and an achievement unpin all
reach the fan-out, and a sweep that dropped nothing reports nothing. The one covered write no test
can reach is a dialogue memory, since that view needs a live component type and a world; it is in-game
smoke like the rest of this module's engine-touching half.

The NPC quest page is split the same way. `NpcQuestSectionsTest` pins the ordering rules a player
notices immediately and a refactor breaks silently - which bucket a status lands in, a finished quest
belonging elsewhere reading as parked rather than offering a button the engine refuses, a repeatable
waiting out its clock reading as locked rather than vanishing, a routed highlight beating a stale
selection while a surviving selection beats the first row. How a reward READS from strings alone is
pinned by `zc-loot`'s `RewardChipsTest`, over kind ids of the test's OWN invention, since naming a
real consumer's kind would disprove the thing being proved. `NpcQuestPageDepsTest` pins that an unfilled seam leaves a
working page and that a filled one throwing costs its own contribution rather than the screen. The
rendering itself, the offer providers and the engine calls behind each button are in-game smoke
territory.

The admin family is pinned the way the commerce one is: `command/ProgressAdminKeysTest` walks the
command package and fails on a key with nothing to resolve it from, on a verb or group with no help
line, and on a runtime status with no word; the engine calls behind each verb (`wipeQuest`,
`wipeAllQuests`, `resetAll`) are pinned one module down beside the engines that own them.
`admin/SystemSwitchesTest` pins the admin page's registry half - additive + live registration,
order-then-id ordering, the unknown-on-throw read, the refuse-without-throw write - while the page
itself is in-game smoke like every other page here.

The tracked-quest HUD is split the same way. `TrackedQuestSnapshotTest` pins what a paint SHOWS
over an in-memory engine (hidden when nothing is pinned, one block per pin, only the current step's
rows and the list advancing, a report-back hand-in with no count, a finished row reading complete,
the slot caps, the deps' switches hiding the panel, titles and lines from the registered sources);
`TrackedQuestHudEventTest` drives the six static event handlers over a recording tracker and pins
one repaint per event for the named player only, the objective event skipped for an unshown quest,
and the uuid registry (a player who left is never repainted, a reconnect replaces the stale one);
`RepaintCoalescerTest` pins the fold (a burst is one paint at the end of the tick, the next request
after it starts a new one, a refusing world leaves nothing phantom-queued, a request during the paint
queues one more); `TrackedQuestHudDepsTest` pins the theme seam (an empty theme is the native look, a
filled colour changes only itself) and every guarded reader. The pin event itself is pinned one
module down in `zc-progression`'s `QuestTrackedEventTest`, through `QuestEvents.publishTo`. The
attach on the native `HudManager`, the paint onto the document and the disconnect handler are in-game
smoke like the rest of this module's engine-touching half.

The text a row is NAMED by is pinned one module down, in `zc-progression`'s `ContentTextArgsTest`,
next to the shared schema that carries it: the args an author bound, the step line a fold composed,
and the resolution through the authored-key seam are all properties of the runtime object now, so
they can be asserted on real values rather than by reading source. That matters here because a page
CANNOT be reached from a test at all - initializing one builds a logger in a static initializer that
refuses to load in a JVM whose log manager is already up, which is why `zc-dialogue`'s page render
guard is written the way it is too.
