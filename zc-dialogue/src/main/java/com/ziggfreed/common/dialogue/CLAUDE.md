# dialogue/

## Registering vocabulary

- The server runs one `DialogueEngine`. A mod adds its actions, conditions and shorthand with `DialogueEngine.registerShared(owner, type)` from its plugin `setup()`; contributions stack, so one option may run two mods' actions.
- Register in `setup()`, never lazily: the server finishes every plugin's setup before `LoadAssetEvent`, and a file naming a `Type` nobody registered fails its read, naming the file.
- Claims are first-wins: a `Type` id, an action class's handler and a condition class's evaluator each belong to the first registrant, and a later one is refused and reported (`registerShared` answers false for a class another mod holds).
- `installQuests` and `installFactors` are singular slots. The library installs the factor slot (the `hytale:` vocabulary) at its own setup, so contribute the ids your conversations gate on through `FactorContributions`, not a private `FactorRegistry`.
- Beneath the quest slot sits `installDefaultQuests`, which zc-objectives fills with `RuntimeDialogueQuests` (the shared quest engine). A consumer whose quests live in that engine installs nothing; one with a rule of its own extends that class and installs the subclass through `installQuests`, never a second copy of its answers.
- `DialogueEngine.builder()` is for an isolated test engine; start each test with `DialogueTestSupport.reset()`, because the type table, the shared engine and the payload suppliers are process-wide.
- A shorthand key is a schema leaf registered with its action (`DialogueActionType.withSugar`) and folded into the option's actions after decode, never a pre-parse rewrite. Authored `Actions` run before the shorthand, and a `Do` array replaces the bare shorthand keys.
- `DialoguePayloads.register(type, owner, supplier)` in `setup()` lets your payload reach a conversation another mod opened; an explicit payload always wins, and the supplier is asked at most once per context.
- `schema/` stays one package: `DialogueTypeTable` writes the package-private fields of the option, start and sugar classes.

## Conversation rules

- `MarkTalked` is the only talk credit: a clicked option fires it, never an open or a render, and it has no shorthand.
- The engine never imports `npc` (only `page/` reads `NpcNames` for the header): talk credit and quest routing leave through the `DialogueTalk` and `DialogueQuestView` seams the npc layer fills.
- `Start` is declared sections on an engine-owned ladder: `First`, a ready quest, an offerable quest, an active quest, `Then`, `Fallback`. A ready quest diverts only when its row exists, and `Ready: true` routes to the character's quest list with that quest highlighted, never an inline hand-in.
- `World` and `Factor` conditions fail closed (an unreadable world, an unknown factor or no registry hides the line); a malformed `Where` reports the shared `WhereValidator` finding, never a dialogue copy of it.
- Under native `Parent`, `Nodes`, `Memories` and `Fragments` merge per key and `Start` replaces whole.
- An extension's `Season` rides each line it hands out (`DialogueOption.getInjectedSeason`), and `optionAvailable` hides the line while that season is not running; the splice never reads a season, so a season turning over needs no re-splice, and a conversation's own lines carry none.
- Tests for `type/`, `schema/` and `state/` stay flat in `com.ziggfreed.common.dialogue`; `state.DialogueMemoriesTest` is the one exception, for its package-private seams.
