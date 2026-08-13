# npc/ - generic NPC spawn + press-F-opens-a-dialogue primitives

Router for `com.ziggfreed.common.npc`. Lifted config-free from the MMO Skill Tree mod (`MmoNpcSpawnService` + `ActionOpenMmoUi`) so a minigame (or eventually the MMO) gets "spawn an NPC whose press-F opens a branching dialogue" without re-deriving it. The dialogue engine + `page/DialoguePage` already live in [`dialogue/`](../dialogue/CLAUDE.md); this package adds only the spawn + the press-F NPC Action half.

## Identity, credit, and the at-NPC surface

The other half of this package: WHO an NPC is, what it means for a conversation with one to count,
and the one value every surface asks its questions through.

- **[`NpcIdentities`](NpcIdentities.java) is the ONE authority for "who is this" and "which ids does
  it answer to".** CONVENTION FIRST, so almost nothing needs authoring: asked about a live NPC it
  walks **its placement** (the stamped placement's `Identity.NpcId`, or the placement id itself when
  it authors none) -> **an identity overlay on its role** (including the role a chain of native
  `Variant`s references, walked through the engine's own `BuilderRoleVariant.getReferenceIndex()`
  loop, cycle-bounded) -> **an overlay on a group it belongs to** (native `NPCGroup` membership) ->
  **its role id in lower case** -> nobody. **The convention is the FLOOR, not a rung above the
  overlays**: both overlay forms are statements an author wrote on purpose, and a convention that beat
  them would leave the group form unable to apply at all, since every NPC already has a role name.
  Role beats group because it is the more specific statement.
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
- **[`TalkCredits`](TalkCredits.java) is where a conversation becomes CREDIT**, over
  [`TalkCredit`](TalkCredit.java) (the record a sink sees) and
  [`TalkCreditSink`](TalkCreditSink.java) (what a mod DOES about it).
  - **Credit is an authored beat.** Nothing credits because a player interacted or because a page
    opened. What the engine gives an author for free is the TARGET (the character in front of them,
    alias set included), never the trigger.
  - **The re-trigger window lives HERE, in front of the sinks.** It is a property of the moment: two
    sinks disagreeing about whether a conversation happened would tick a quest while the statistic
    counting the same conversations stayed put, and nothing would report it. In memory, cleared on
    disconnect by the plugin, never persisted - "talk to anyone" must count again tomorrow. Claimed
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
  Registered by THIS library's plugin (`NpcActions.registerTalkCredit`), not by a consumer, because
  unlike `ZigOpenDialogue` it needs nothing wired.
- **[`NpcTalkDialogue`](NpcTalkDialogue.java)** is the one line joining a conversation's
  `MarkTalked` beat to all of that, installed by the plugin at setup. It exists so the edge stays
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
  - **`completionHandOff` / `playCompletion` are the at-NPC form of the completion hand-off** (the
    policy itself is [`dialogue/quest/QuestCompletionRouting`](../dialogue/quest/CLAUDE.md); this
    package reaches DOWN into it, never the reverse). Both route on the character's PRIMARY id rather
    than on whichever alias took the hand-in, so the conversation's `@self` targets and its header
    name the character the player is looking at. Both are DEFAULT methods, so a fourth party's own
    encounter stays source-compatible and skips the beat rather than guessing at one. The
    conversation form keeps `playCompletion`'s false default deliberately: a conversation does not
    hand off to itself, and a `TurnIn` beat inside a dialogue routes onward with `Goto`.
- **[`placement/`](placement/CLAUDE.md) is the full NPC PLACEMENT ENGINE** built on top of this package: one asset type (`Server/ZiggfreedCommon/NpcPlacements/*.json`) plus a ledger, a component, a gate chain, a reconcile sweep, and four open registries (bindings, factors, custom anchors, gates), so "put an NPC here, in these worlds, under these conditions, and keep exactly one of it standing" is content rather than Java. Start there for anything placement-shaped; this package keeps only the primitives it composes. **Its correctness rule in one line: never place from absence alone** (a chunk unload removes the entity from the store, so absence and asleep look identical).
- **[`NpcSpawnService.spawnRole`](NpcSpawnService.java) is the ONE spawn primitive** (role-guard via `NPCPlugin.hasRoleName`/`getIndex`, then `spawnEntity`). THREE overloads: fire-and-forget; with an injected `TriConsumer<NPCEntity, Ref, Store>` post-spawn callback (e.g. to record the spawned UUID - the MMO records placements; a per-round minigame NPC is fire-and-forget); and with BOTH engine hooks, `preAdd` against the pre-commit `Holder` plus `postSpawn` against the committed ref (the placement engine attaches its identity component on the holder so there is no live-ref race, then reads the uuid post-commit). The 6-argument form is the 7-argument one with a null `preAdd`, so existing callers are unchanged. `resolveSpawnPosition` (world spawn point, else the player's position) + `despawn(store, uuid)` are lifted verbatim. **World-thread only** (`spawnEntity`/`removeEntity` run outside the ECS window, i.e. inside `world.execute`); every method is try-guarded so a throw never breaks player-ready / chunk load. The MMO's `MmoNpcPlacementStore` record + once-per-world hub gate did NOT lift (a singleton file would clobber across mods) - a consumer owns its own placement/idempotency policy on top, or uses `placement/` which owns that policy generically.
- **[`ActionOpenDialogue`](ActionOpenDialogue.java)** (registered `"ZigOpenDialogue"`, modeled on the engine's `ActionOpenBarterShop`): press-F reads `getInteractionIterationTarget` + `PlayerRef`/`Player`, resolves the consumer's [`DialoguePageDeps`](../dialogue/page/DialoguePageDeps.java) LAZILY from [`NpcDialogueDepsRegistry`](NpcDialogueDepsRegistry.java), and opens [`DialoguePage`](../dialogue/page/DialoguePage.java) on the NPC's own ref. Carries only data: `Dialogue` (id), optional `ContextNpc` (`@self` resolution + header), optional `DepsKey`. A blank `Dialogue` or a missing/null deps provider degrades to a logged no-op.
- **[`NpcAutoSpawn`](NpcAutoSpawn.java) is the generic, role-keyed, once-per-world auto-spawn** (the config-free lift of kweebec's guide triple - config/spawn/placement-store - folded into one helper so a NEW consumer, e.g. the Clash Host, auto-spawns without cloning). A consumer hands an `AutoSpawnSpec` (`roleKey`/`roleAsset`/`worlds`/offset/`yaw`) + a placement dir; `ensureSpawned(world, spec, dir)` resolves the world spawn point, offsets it, places the role via `NpcSpawnService`, records the UUID, and marks the placement, idempotent across restarts (`init(dir)` pre-loads the marker, optional). Idempotency persists in the sibling [`NpcPlacementStore`](NpcPlacementStore.java) (the generic lift of `KweebecGuidePlacementStore`, keyed by `(world, roleKey)` not world alone, one cached store per dir). **World-thread only** (the caller wraps it, e.g. in `world.execute` on `PlayerReadyEvent`); fully try-guarded. Kweebec's own guide stays on its own triple - it wires to `NpcAutoSpawn` in a later wave.
- **[`NpcDialogueDepsRegistry`](NpcDialogueDepsRegistry.java) is the decoupling seam.** A role asset decodes long before a consumer's deps exist, so the action stores only ids and pulls a `Supplier<DialoguePageDeps>` at press-F time. Keyed (normalized trim + lower-case) so >1 consumer in one server never collide; the single-consumer case uses `DEFAULT_KEY`. The consumer calls `NpcDialogueDepsRegistry.set(MyDialogue::deps)` once at setup.
- **[`BuilderActionOpenDialogue`](BuilderActionOpenDialogue.java)** (extends the engine `BuilderActionBase`): `readConfig` reads the three `StringHolder`s + `requireInstructionType(Interaction)` so the action runs ONLY inside a role's `InteractionInstruction`.
- **[`NpcActions`](NpcActions.java) holds both registrations, and WHO calls each is not arbitrary.** `register()` registers `ZigOpenDialogue` and is CONSUMER-called ONCE in its plugin `setup()` BEFORE its NPC-role assets load (else `{ "Type": "ZigOpenDialogue" }` fails to parse), alongside `NpcDialogueDepsRegistry.set(...)`: opening a dialogue needs that consumer's wiring to mean anything, and `ziggfreed-common`'s plugin cannot know it. `registerTalkCredit()` registers `ZigTalkCredit` and is COMMON-called from this library's own plugin, because crediting a conversation needs nothing from anybody. Both are idempotent + guarded (a second consumer's call is a no-op; a failure logs, never throws).

## Tests

Pure decision cores only. `NpcIdentitiesTest` pins the ladder's precedence, the primary-versus-alias
asymmetry (content aimed at the shared name resolves at the placement, and NOT the reverse), the
union two placements sharing a primary contribute, and that a reload is visible on the next lookup;
`NpcIdentityValidatorTest` pins every finding including the case-only collision and which file a
duplicate reports against; `TalkCreditsTest` pins the re-trigger window (per player, per id,
case-insensitive, cleared on disconnect) and sink isolation. The rungs that need a running server (an
NPC entity's role, native group membership) and the assembly of a credit from a live player are smoke
territory, matching the rest of the mod's split.

**Role JSON shape** (the consumer ships it in its asset pack, `Type: "Generic"`): an `InteractionInstruction` `HasInteracted` block whose `Actions` are `[LockOnInteractionTarget, { "Type": "ZigOpenDialogue", "Dialogue": "<id>" }, { "State": "$Interaction" }]`, plus the `$Interaction`-reset block (load-bearing for repeat press-F). Carry a `NameTranslationKey` or the name renders as the raw role id. Copy the MMO's `MMO_QuestGiver.json` shape (minus the `Target: auto` placement-registry indirection - a minigame hardcodes its dialogue id).
