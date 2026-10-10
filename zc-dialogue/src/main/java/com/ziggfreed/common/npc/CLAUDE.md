# npc/ - who an NPC is, what counts as talking to one, and the spawn and press-F primitives

The placement engine (put an NPC somewhere and keep exactly one standing) is `placement/`. This package holds the identity, naming and talk-credit layer every surface asks through, plus the spawn and press-F primitives placement builds on.

## Identity and names

- `NpcIdentities` answers who an NPC is: the placement's `Identity.NpcId` (or, with none authored, the role that placement names), then an identity overlay on its role (following native `Variant` chains), then an overlay on a group it belongs to, then its role id. The convention is the floor, never a rung above the overlays. An unauthored `NpcId` means the character IS its role, so two placements of one role are one character.
- An alias is one-way: what a character responds to, never what it is (a nameplate and a waypoint use the primary). Authored case is kept, every membership test is case-insensitive, and `allDeclaredNpcIds()` cannot include convention ids, so treat an id missing from it as unverifiable, never as an error.
- `NpcNames` reads only the role's `NameTranslationKey`. When none resolves, null is the answer, never a guessed `npcs.<id>.name` key or a prettified id. An audit asks `canResolveNames()` first: false means there is no role registry to ask yet, not that no role carries a name.

## Talk credit

- Credit is an authored beat: a conversation's `MarkTalked`, or the `ZigTalkCredit` role action, whose `Npc` is required (a blank one credits nothing). Never credit on a press-F or a page open.
- The re-trigger window lives in `TalkCredits`, in front of every sink, so two sinks can never disagree about whether a conversation happened. It is keyed by (player, id, qualifier), so an unqualified credit never swallows a qualified line.
- The library's own sink (`LIBRARY_SINK_ID`, filled by zc-objectives' `ZigTalkProducer`) always runs, beside any consumer sink, so a consumer sink never dispatches `TALK_TO_NPC` itself; `register` refuses the reserved id, and a mod that only watches listens for `NpcTalkedEvent` instead.

## Seams and wiring

- The dialogue engine never imports `npc`: it declares seams (`DialogueTalk`, `DialogueQuestView`) that this package fills. Only `DialoguePage` reads `NpcNames` directly, for its header.
- A consumer calls `NpcActions.register()` (`ZigOpenDialogue`) in its `setup()`, before its NPC role assets load, or a role naming that action fails to parse. The library registers `ZigTalkCredit` itself.
- The conversation page reads only process-wide state (`DialogueEngine.shared()`, `DialoguePayloads`, `NpcNames`, `ContentKeys`). Never add a per-consumer page registration: it lets one talking mod's namespace hide another mod's lines.
- `NpcSpawnService` runs on the world thread, inside `world.execute`. Standing an NPC somewhere and keeping only one belongs to `placement/`, which owns idempotency.
- `NpcSpawnService.spawnRole` never adds into a chunk section that is not ticking: on Update 7 such an NPC is parked on the spot with its post-spawn unrun, and it returns on its own beside any retry. It wakes a section in memory (zc-world's `world/TickingSections.ensureTicking`) and refuses one that is not, with a WARNING; a caller placing where no player is goes through `TickingSections.whenTicking`, as `NpcAutoSpawn` does.
- A world spawn point is a future on Update 7 (`ISpawnProvider.getSpawnPointAsync`; a fitted provider loads the spawn column first): ask and read it through `SpawnPoints` (`ask`, `now`, `failureOf`), continue with `SpawnPoints.whenLanded` on the world executor, and never `join()` it on the world thread. `NpcSpawnService.resolveSpawnPosition` answers only a point already at hand, else the player's position; `NpcAutoSpawn` places once the point lands, checking its marker again first.
- `NpcEncounter.canCompleteHere` is the site question, separate from readiness: a finished, fully carried quest can still belong to another character. The completion hand-off routes on the character's primary id, never on the alias that took the hand-in.

## A press-F dialogue role

`Type: "Generic"`, with an `InteractionInstruction` whose `HasInteracted` block runs `LockOnInteractionTarget`, `{"Type": "ZigOpenDialogue", "Dialogue": "<id>"}` and `{"Type": "State", "State": "$Interaction"}`, plus the `$Interaction` state instruction that returns the NPC to its normal state (repeat press-F depends on it), and a `NameTranslationKey`, or the nameplate shows the raw role id. Kweebec Nightmare's `KweebecNightmare_Guide.json` role is a working copy.

A quest giver needs no body of its own: it is a `Variant` of the library's `Template_Zig_QuestGiver` (zc-dialogue's `Server/NPC/Roles/Passive/`), whose press F runs `ZigPlacementInteract` (`Open: Quests` when nothing placed it). Its `Modify` names only the five parameters (`Appearance`, `NameTranslationKey`, `Weapons`, `OffHand`, `DefaultOffHandSlot`); `Armor` and the `Hint` are literal, so a giver with its own prompt needs a full body. The engine checks a `Variant`'s `Modify` against its immediate `Reference`'s own `Parameters` only, so a consumer's template over this one re-declares each parameter it passes on and forwards it as a same-name `Compute`. `ZigRoleTemplateTest` pins the template.
