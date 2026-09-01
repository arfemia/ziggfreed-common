# npc/ - generic NPC spawn + press-F-opens-a-dialogue primitives

Router for `com.ziggfreed.common.npc`. Lifted config-free out of a consumer mod's own spawn + press-F plumbing, so a minigame (or the MMO) gets "spawn an NPC whose press-F opens a branching dialogue" without re-deriving it. The dialogue engine + `page/DialoguePage` already live in [`dialogue/`](../dialogue/CLAUDE.md); this package adds only the spawn + the press-F NPC Action half.

## Identity, credit, and the at-NPC surface

The other half of this package: WHO an NPC is, what it means for a conversation with one to count,
and the one value every surface asks its questions through.

- **[`NpcIdentities`](NpcIdentities.java) is the ONE authority for "who is this" and "which ids does
  it answer to".** CONVENTION FIRST, so almost nothing needs authoring: asked about a live NPC it
  walks **its placement** (the stamped placement's `Identity.NpcId`, or the ROLE that placement names
  when it authors none) -> **an identity overlay on its role** (including the role a chain of native
  `Variant`s references, walked through the engine's own `BuilderRoleVariant.getReferenceIndex()`
  loop, cycle-bounded) -> **an overlay on a group it belongs to** (native `NPCGroup` membership) ->
  **its role id** -> nobody. **The convention is the FLOOR, not a rung above the
  overlays**: both overlay forms are statements an author wrote on purpose, and a convention that beat
  them would leave the group form unable to apply at all, since every NPC already has a role name.
  Role beats group because it is the more specific statement.
  - **An unauthored `Identity.NpcId` means the character IS its role**, so the first and the last
    rung give the SAME answer for the same role and a character cannot change name depending on
    whether a placement stood it up. Two placements of one role are two standings of one character:
    a quest bound to it is offered, credited and handed in at either. An authored id opts OUT and
    becomes a character nothing else answers to, which is how a step is scoped to one standing.
    A placement naming neither an id nor a role stands nobody up and answers to nothing - inventing
    an id from its file name would mint a character never in the world for content to bind to.
    Both rungs keep the role id spelled exactly as it is: a display key built from an id
    (`npcs.<id>.name`, `server.npcRoles.<id>.name`) is looked up verbatim, while every membership
    test here is case-insensitive.
  - **Primary versus alias is an asymmetry, not a synonym list.** The primary is what a character IS
    (a nameplate, a waypoint); an alias is only what it RESPONDS to, and it goes one way. That is what
    lets the same character stand in two worlds, each with its own primary, both answering to the
    shared name. Authored case is PRESERVED (a consumer's content matching may be case-sensitive);
    every membership test is case-INSENSITIVE, matching how the engine compares a role name.
  - **The reverse index is required, not an optimisation.** Every answer-set read goes through ONE
    lazily-built index over the folded placements and overlays, dropped by either config's merge.
    These reads sit on quest, dialogue and UI paths that run per objective, per quest and inside page
    sort comparators, where a scan per call is a scan per row per comparison. Both the index build and
    `placementsForNpcId` are sorted by id, so a surface listing several placements is stable across
    restarts.
  - `allDeclaredNpcIds()` deliberately CANNOT include the convention ids (the set of roles on a server
    is not enumerable from here), so a consumer treating an unlisted id as an error would be wrong -
    treat it as unverifiable.
- **[`NpcIdentityAsset`](NpcIdentityAsset.java) + [`NpcIdentityConfig`](NpcIdentityConfig.java)** -
  Pattern A at `Server/ZiggfreedCommon/NpcIdentities/<id>.json`, the OVERLAY the convention cannot
  express: aliases, one character across two roles, a rename. `Role` (exact, case-insensitive) and
  `Group` (a native `NPCGroup`, covering a family) are two SELECTORS, not a mode; a role match wins
  because it is the more specific statement. A collision resolves to the alphabetically first file id
  so the answer is stable across restarts, and
  **[`NpcIdentityValidator`](NpcIdentityValidator.java)** names both files - plus the file that
  selects nothing, the one that names no id, and the scout-found case-only role collision (two roles
  differing by capitals are ONE role to the engine, so an author comparing the files sees nothing
  wrong).
- **[`NpcNames`](NpcNames.java) is the ONE display-name surface.** A character's name is authored in
  exactly one place, the NPC role's `NameTranslationKey`, and the nameplate, the conversation header,
  a quest's "Talk to X" line and the validators all read THAT key through here, so the header and the
  nameplate structurally cannot disagree. Asked about a character it walks: **a live NPC** (its BUILT
  role already carries the resolved key, byte-for-byte what the nameplate renders) -> **the id,
  resolved statically** (whichever placements answer to it, in [`NpcIdentities`](NpcIdentities.java)
  order, each read for the role it stands, and that role's builder walked for the key it would
  resolve to) -> **the id AS a role id** (the same character-IS-its-role default) -> nobody.
  - **Null is the answer, never a rescue.** No `npcs.<id>.name` guess, no prettified id, no case-fold
    retry: a guessed key renders as its own raw text and reads to a player as a name somebody chose,
    so a wrong name is worse than a blank one. A character with no resolvable key is the placement
    validator's `NO_DISPLAY_NAME`, reported once at the late audit.
  - **`nameFor` is the Message, `nameKeyFor` the key.** The Message is built FROM the key and resolved
    client-side in the player's own locale (the server never resolves or stores one); the key twin is
    what a validator asks for. `nameKeyOfPlacement` and `nameKeyOfRole` are the other two ways in, for
    a caller holding a placement id or a role id rather than a character.
  - **The static walk is ONE guarded method**, because it is assembled from the engine's builder
    pieces rather than copied from a first-party caller that resolves a name without an entity:
    role id -> builder index -> the cached role BUILDER (declarative; a built `Role` needs a live
    entity) -> a `Variant`'s MERGED modifier scope, which accumulates every hop's `Modify` block (the
    engine restores the context's previous scope before handing that scope back, so setting it
    afterwards is load-bearing rather than tidy) -> the chain's terminal non-`Variant` base, which is
    the builder whose field holds the key -> the key read against that scope, which is what resolves a
    `{"Compute":"NameTranslationKey"}` binding. A plain role skips both middle steps and uses its own
    parameters' scope, mirroring what the engine does at spawn. One wrong assumption there costs a
    blank name and one fine-level line, never a throw into a page render or an audit loop.
  - **Answers are cached POSITIVELY, per role id** (role builders load once per boot, so a resolved
    key cannot go stale under a running server). Nothing negative is cached, because a pack registered
    later brings roles with it and a remembered "no" would outlive the reason for it. `invalidate()`
    drops the cache for a role hot-reload. Which ROLE a placement stands is never cached, so a
    placement reload is visible on the next lookup.
  - **`canResolveNames()` is the cannot-tell probe** an audit asks FIRST. False means there is no role
    registry to ask (a unit JVM, a call before the NPC plugin is up, a failed read) and never "no role
    carries a name", the same convention as
    [`world/WorldIdentity.loadedWorlds()`](../../../../../../../../zc-world/src/main/java/com/ziggfreed/common/world/WorldIdentity.java)
    answering with an empty list.
- **[`TalkCredits`](TalkCredits.java) is where a conversation becomes CREDIT**, over
  [`TalkCredit`](TalkCredit.java) (the record a sink sees) and
  [`TalkCreditSink`](TalkCreditSink.java) (what a mod DOES about it).
  - **Credit is an authored beat.** Nothing credits because a player interacted or because a page
    opened. What the engine gives an author for free is the TARGET (the character in front of them,
    alias set included), never the trigger.
  - **The re-trigger window lives HERE, in front of the sinks.** It is a property of the moment: two
    sinks disagreeing about whether a conversation happened would tick a quest while the statistic
    counting the same conversations stayed put, and nothing would report it. In memory, cleared on
    disconnect by `NpcBootstrap.setupTalkCredit`'s own `PlayerDisconnectEvent` listener, never
    persisted - "talk to anyone" must count again tomorrow. Claimed
    PER ID, so an alias fired beside its primary is de-duped on its own terms.
  - A registry rather than only an event because a sink is a WRITE: it wants attribution, per-sink
    isolation, and one decision about whether the moment counts. The WATCH half is the native
    [`NpcTalkedEvent`](NpcTalkedEvent.java) (`dispatchFor` + `hasListener`), fired once per credited
    conversation after the sinks, for anything that registers nothing.
- **[`ActionTalkCredit`](ActionTalkCredit.java)** (registered `"ZigTalkCredit"`) is the ESCAPE HATCH
  for a character with no conversation to put a `MarkTalked` beat in: a role's
  `InteractionInstruction` carries `{ "Type": "ZigTalkCredit", "Npc": "<id>" }` and a press-F credits
  that character through the same [`TalkCredits`](TalkCredits.java) path, alias set, window and sinks
  included. It is the direct analogue of the engine's own `{"Type":"CompleteTask"}` NPC action, which
  is how first-party content credits an objective from inside a behaviour tree. **`Npc` is required
  and a blank one credits nothing**, unlike a dialogue beat's optional `Target`: a conversation always
  knows who it is with, a role does not, and a guess here would credit every NPC wearing the role.
  Registered by THIS library itself (`NpcBootstrap.setupTalkCredit` calls
  `NpcActions.registerTalkCredit`), not by a consumer, because
  unlike `ZigOpenDialogue` it needs nothing wired.
- **[`NpcTalkDialogue`](NpcTalkDialogue.java)** is the one line joining a conversation's
  `MarkTalked` beat to all of that, installed by `NpcBootstrap` at setup. It exists so the edge stays
  one-way: the dialogue engine resolves WHO a beat is about and stops at
  [`DialogueTalk`](../dialogue/DialogueTalk.java), and this package fills that seam. **Do not import
  `npc` from `dialogue`** - the direction is `npc -> dialogue`, and a seam is how anything goes back.
- **[`NpcEncounter`](NpcEncounter.java) + [`NpcEncounters`](NpcEncounters.java)** - everything a
  surface needs about the character a player is standing at, obtained ONCE: who they are, what is on
  offer, what is ready to hand in here, hand it in, credit the conversation. Three factories (from an
  NPC entity, from an id, from the open conversation), each taking the same
  [`DialogueQuests`](../dialogue/quest/CLAUDE.md) seam the consumer already wired, so an NPC panel and
  a conversation can never disagree about what a player may hand in. The answer set is resolved once
  per encounter rather than per question.
  - **`canCompleteHere(questId)` is the SITE question**, asked separately from readiness and
    possession because they are different questions: a quest can be finished and fully carried and
    still not completable at THIS character, because it says to report back somewhere else. It walks
    the same answer set the readiness reads do, over the quest reader's own `canCompleteAt`, and it is
    a DEFAULT on the interface (permissive, matching the reader) so a fourth party's own encounter
    stays source-compatible. A character with no id answers NO: there is no HERE to satisfy, and a
    surface with nobody in front of the player is asking a site-free question of the reader instead.
  - **`completionHandOff` / `playCompletion` are the at-NPC form of the completion hand-off** (the
    policy itself is [`dialogue/quest/QuestCompletionRouting`](../dialogue/quest/CLAUDE.md); this
    package reaches DOWN into it, never the reverse). Both route on the character's PRIMARY id rather
    than on whichever alias took the hand-in, so the conversation's `@self` targets and its header
    name the character the player is looking at. Both are DEFAULT methods, so a fourth party's own
    encounter stays source-compatible and skips the beat rather than guessing at one. The
    conversation form keeps `playCompletion`'s false default deliberately: a conversation does not
    hand off to itself, and a `TurnIn` beat inside a dialogue routes onward with `Goto`.
- **[`NpcDestinations`](NpcDestinations.java) seeds the two destinations every server has** into the
  shared routing vocabulary ([`ui/route/Destinations`](../../../../../../../../zc-presentation/src/main/java/com/ziggfreed/common/ui/route/Destinations.java)):
  `Dialogue` (`{Dialogue}`, opening a `DialoguePage` on the character the moment is already about)
  and `Quests` (`{Npc?}`, the character's own list). They live HERE rather than in the vocabulary
  because this is where their behaviour is - the library owns the dialogue engine, and the quest list
  rides a seam - which keeps the registry itself domain-free. Registered by common's own plugin at
  `setup()`, since neither needs anything a consumer has to wire, and **neither ever names the
  character when the moment already knows**: an author writes `"Open": "Quests"` or
  `{"Type":"Dialogue","Dialogue":"<id>"}` and the identity travels in the `DestinationContext`.
- **[`NpcQuestListHosts`](NpcQuestListHosts.java) + [`NpcQuestListHost`](NpcQuestListHost.java)** -
  the open table the `Quests` destination routes through, the sibling of
  [`../dialogue/quest/QuestDialogueHosts`](../dialogue/quest/CLAUDE.md) one step earlier in a quest's
  life. Nothing is pre-seeded: a server with no quest UI opens nothing, which is the honest answer,
  and the first host (in sorted id order, so it survives a restart) that TAKES the screen wins. **A
  host is handed a nullable `highlightQuestId` beside the character**, which travels the whole way
  from a conversation's `Start` quest row through the `Quests` destination's `Highlight` leaf: a
  ready quest never takes a conversation over by itself, so the beat that surfaces one routes here
  naming it and the player lands looking at that quest rather than at whichever row sorted first.
  Singling the row out is a courtesy, never a condition - a host that cannot do it still opens.
- **[`placement/`](placement/CLAUDE.md) is the full NPC PLACEMENT ENGINE** built on top of this package: one asset type (`Server/ZiggfreedCommon/NpcPlacements/*.json`) plus a ledger, a component, a gate chain, a reconcile sweep, and three open registries (factors, custom anchors, gates - what press-F opens is the shared `ui/route/Destinations` vocabulary, not a placement registry), so "put an NPC here, in these worlds, under these conditions, and keep exactly one of it standing" is content rather than Java. Start there for anything placement-shaped; this package keeps only the primitives it composes. **Its correctness rule in one line: never place from absence alone** (a chunk unload removes the entity from the store, so absence and asleep look identical).
- **[`NpcSpawnService.spawnRole`](NpcSpawnService.java) is the ONE spawn primitive** (role-guard via `NPCPlugin.hasRoleName`/`getIndex`, then `spawnEntity`). THREE overloads: fire-and-forget; with an injected `TriConsumer<NPCEntity, Ref, Store>` post-spawn callback (e.g. to record the spawned UUID - the MMO records placements; a per-round minigame NPC is fire-and-forget); and with BOTH engine hooks, `preAdd` against the pre-commit `Holder` plus `postSpawn` against the committed ref (the placement engine attaches its identity component on the holder so there is no live-ref race, then reads the uuid post-commit). The 6-argument form is the 7-argument one with a null `preAdd`, so existing callers are unchanged. `resolveSpawnPosition` (world spawn point, else the player's position) + `despawn(store, uuid)` are lifted verbatim. **World-thread only** (`spawnEntity`/`removeEntity` run outside the ECS window, i.e. inside `world.execute`); every method is try-guarded so a throw never breaks player-ready / chunk load. The MMO's `MmoNpcPlacementStore` record + once-per-world hub gate did NOT lift (a singleton file would clobber across mods) - a consumer owns its own placement/idempotency policy on top, or uses `placement/` which owns that policy generically.
- **[`ActionOpenDialogue`](ActionOpenDialogue.java)** (registered `"ZigOpenDialogue"`, modeled on the engine's `ActionOpenBarterShop`): press-F reads `getInteractionIterationTarget` + `PlayerRef`/`Player` and opens [`DialoguePage`](../dialogue/page/DialoguePage.java) on the NPC's own ref, through [`DialogueOpener`](../dialogue/page/DialogueOpener.java) so a `Start` that routes elsewhere can hand the screen over. Carries only data: `Dialogue` (id) and an optional `ContextNpc` (`@self` resolution + header). A blank `Dialogue` degrades to a logged no-op. **Nothing else has to be registered for it to work** - the page is built from process-wide state, so a role naming a conversation is the whole of the wiring.
- **[`NpcAutoSpawn`](NpcAutoSpawn.java) is the generic, role-keyed, once-per-world auto-spawn** (the config-free lift of kweebec's guide triple - config/spawn/placement-store - folded into one helper so a NEW consumer, e.g. the Clash Host, auto-spawns without cloning). A consumer hands an `AutoSpawnSpec` (`roleKey`/`roleAsset`/`worlds`/offset/`yaw`) + a placement dir; `ensureSpawned(world, spec, dir)` resolves the world spawn point, offsets it, places the role via `NpcSpawnService`, records the UUID, and marks the placement, idempotent across restarts (`init(dir)` pre-loads the marker, optional). Idempotency persists in the sibling [`NpcPlacementStore`](NpcPlacementStore.java) (the generic lift of `KweebecGuidePlacementStore`, keyed by `(world, roleKey)` not world alone, one cached store per dir). **World-thread only** (the caller wraps it, e.g. in `world.execute` on `PlayerReadyEvent`); fully try-guarded. Kweebec's own guide stays on its own triple - it wires to `NpcAutoSpawn` in a later wave.
- **A conversation belongs to the SERVER, not to whichever mod opened it.** There was once a registry of per-consumer page bundles here, keyed so several mods could each register one, with an un-keyed default for the ordinary single-consumer case. On a server running two talking mods that default was a genuine collision: the later mod to start took it, and every conversation on the server was then painted through that mod's namespace, so the other mod's authored keys all missed and its lines read as raw keys - or crashed the client outright, since an unresolved label reaches a String-only sink. It is gone. The page reads `DialogueEngine.shared()`, the one store, [`../dialogue/DialoguePayloads`](../dialogue/CLAUDE.md) by payload class, `NpcNames` for the header name, and `ContentKeys` for authored text - all additive, all process-wide, nothing to claim and nothing to collide over.
- **[`BuilderActionOpenDialogue`](BuilderActionOpenDialogue.java)** (extends the engine `BuilderActionBase`): `readConfig` reads the two `StringHolder`s (`Dialogue` + `ContextNpc`) + `requireInstructionType(Interaction)` so the action runs ONLY inside a role's `InteractionInstruction`.
- **[`NpcActions`](NpcActions.java) holds both registrations, and WHO calls each is not arbitrary.** `register()` registers `ZigOpenDialogue` and is CONSUMER-called ONCE in its plugin `setup()` BEFORE its NPC-role assets load (else `{ "Type": "ZigOpenDialogue" }` fails to parse). `registerTalkCredit()` registers `ZigTalkCredit` and is COMMON-called from this package's own `NpcBootstrap`, because crediting a conversation needs nothing from anybody. Both are idempotent + guarded (a second consumer's call is a no-op; a failure logs, never throws).

## Tests

Pure decision cores only. `NpcIdentitiesTest` pins the ladder's precedence, the primary-versus-alias
asymmetry (content aimed at the shared name resolves at the placement, and NOT the reverse), the
union two placements sharing a primary contribute, and that a reload is visible on the next lookup;
`NpcIdentityValidatorTest` pins every finding including the case-only collision and which file a
duplicate reports against; `TalkCreditsTest` pins the re-trigger window (per player, per id,
case-insensitive, cleared on disconnect) and sink isolation; `NpcNamesTest` pins everything ABOVE the
name walk (which placement answers for a character, which role it stands, the role fallback, case),
the no-invention rule, and the positive-only cache, and asserts the walk itself degrades to null in a
unit JVM rather than throwing. The rungs that need a running
server (an NPC entity's role, native group membership, a role builder's resolved name key) and the
assembly of a credit from a live player are smoke territory, matching the rest of the mod's split.

**Role JSON shape** (the consumer ships it in its asset pack, `Type: "Generic"`): an `InteractionInstruction` `HasInteracted` block whose `Actions` are `[LockOnInteractionTarget, { "Type": "ZigOpenDialogue", "Dialogue": "<id>" }, { "State": "$Interaction" }]`, plus the `$Interaction`-reset block (load-bearing for repeat press-F). Carry a `NameTranslationKey` or the name renders as the raw role id. Copy the MMO's `MMO_QuestGiver.json` shape (minus the `Target: auto` placement-registry indirection - a minigame hardcodes its dialogue id).
