# dialogue/

## Registering vocabulary

- The server runs one `DialogueEngine`. A mod adds its actions, conditions and shorthand with `DialogueEngine.registerShared(owner, type)` from its plugin `setup()`; contributions stack, so one option may run two mods' actions.
- Register in `setup()`, never lazily: the server finishes every plugin's setup before `LoadAssetEvent`, and a file naming a `Type` nobody registered fails its read, naming the file.
- Claims are first-wins: a `Type` id, an action class's handler and a condition class's evaluator each belong to the first registrant, and a later one is refused and reported (`registerShared` answers false for a class another mod holds).
- `installQuests` and `installFactors` are singular slots. The library installs the factor slot (the `hytale:` vocabulary) at its own setup, so contribute the ids your conversations gate on through `FactorContributions`, not a private `FactorRegistry`.
- `DialogueEngine.builder()` is for an isolated test engine; start each test with `DialogueTestSupport.reset()`, because the type table, the shared engine and the payload suppliers are process-wide.
- A shorthand key is a schema leaf registered with its action (`DialogueActionType.withSugar`) and folded into the option's actions after decode, never a pre-parse rewrite. Authored `Actions` run before the shorthand, and a `Do` array replaces the bare shorthand keys.
- `DialoguePayloads.register(type, owner, supplier)` in `setup()` lets your payload reach a conversation another mod opened; an explicit payload always wins, and the supplier is asked at most once per context.
- `schema/` stays one package: `DialogueTypeTable` writes the package-private fields of the option, start and sugar classes.

## Conversation rules

- `MarkTalked` is the only talk credit: a clicked option fires it, never an open or a render, and it has no shorthand.
- The engine never imports `npc` (only `page/` reads `NpcNames` for the header): talk credit and quest routing leave through the `DialogueTalk` and `DialogueQuestView` seams the npc layer fills.
- `Start` is declared sections on an engine-owned ladder: `First`, a ready quest, an offerable quest, an active quest, `Then`, `Fallback`. A ready quest diverts only when its row exists, and `Ready: true` routes to the character's quest list with that quest highlighted, never an inline hand-in.
- `page/DialogueOpener` resolves a routed beat before the page exists, so a `Pick` draws once per open.
- A `Start` beat's `OnceId` files its `Once` under that name instead of its screen, so beats naming one OnceId share one claim (several wordings of one daily greeting); its `Actions` run once at the spend (a chosen line, a line an extension added, or Farewell; never Escape), before the chosen line's own actions. Both need a `Once` (`BEAT_ACTIONS_WITHOUT_ONCE`, `BEAT_ONCE_ID_WITHOUT_ONCE`).
- `World` and `Factor` conditions fail closed (an unreadable world, an unknown factor or no registry hides the line); a malformed `Where` reports the shared `WhereValidator` finding, never a dialogue copy of it.
- Under native `Parent`, `Nodes`, `Memories` and `Fragments` merge per key and `Start` replaces whole.
- A screen's options splice in a fixed order: its own `Options`, then every group whose `On` selects it, then every `DialogueExtensions` line that lands on it (extension id order), then its `IncludeOptions`. A shared `DialogueFragments` file is pull-only (lines, no `On` or `Include`); a `DialogueExtensions` file is the one store that pushes lines into other conversations (`Dialogues`, then `On`, else the screens a conversation opens on, `NpcDialogue.openingScreens`). Both stores load before `Dialogues` (`FrameworkStoreOrderTest`), and the splice runs at decode, so a reload of either re-splices every conversation through `DialogueAssetStore.mergeFragments`/`mergeExtensions`. The splice starts from what each screen authored, so it is safe to repeat.
- A line an extension adds is a copy marked `getInjectedBy()`: its `Once` keys by the extension (`once:x:`), never by the screen it shows on, and the audit reads it once against its extension (`checkExtensions`) and skips it in every host walk.
- A `PerConversation` Once on an extension line adds the host conversation's id to that key (`once:x:<extension>:<dialogue>:`), so each conversation spends it on its own and characters sharing a conversation share it; on a conversation's own line the knob changes nothing.
- An extension's `Season` rides each line it hands out (`DialogueOption.getInjectedSeason`), and `optionAvailable` hides the line while that season is not running; the splice never reads a season, so a season turning over needs no re-splice, and a conversation's own lines carry none.
- `DialoguePage` reads the player from its `PlayerRef`, never from the `ref` it was opened on, which is the NPC (`DialoguePageRenderSubjectTest`).
- Node text goes on `#NodeText.TextSpans`, never `.Text`, so markup renders. An option row is a `TextButton`, so an option's colour comes from its `DialogueOptionStyle` or its `Presentation`, never markup.
- Every exit path from the page sends a response.
- Tests for `type/`, `schema/` and `state/` stay flat in `com.ziggfreed.common.dialogue`; `state.DialogueMemoriesTest` is the one exception, for its package-private seams.
