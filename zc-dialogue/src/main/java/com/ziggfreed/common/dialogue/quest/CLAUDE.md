# dialogue/quest/ - the quest-aware conversation vocabulary

Router for `com.ziggfreed.common.dialogue.quest`. The lines every quest giver needs, shipped with the generic vocabulary rather than re-invented per mod: is this quest at that point yet, can it be handed in HERE, is anything ready to hand in here, take it on, hand it in.

| Type | Authored as | Answers / does |
|---|---|---|
| `QuestState` | `{"Type":"QuestState","Quest":"x","State":"ACTIVE"}` or `"States":[...]` | the EFFECTIVE status, so a finished daily off cooldown re-offers itself |
| `ReadyToTurnIn` | `{"Type":"ReadyToTurnIn","Quest":"x"}` | can hand THIS one in here, in full, carrying what it asks for |
| `HasReadyToTurnIn` | `{"Type":"HasReadyToTurnIn"}` | can hand ANY quest in here (one line that never needs maintaining) |
| `HasOfferableQuests` | `{"Type":"HasOfferableQuests"}` | has anything the player could take on right now (SHIPPED UNREGISTERED, see below) |
| `AcceptQuest` | `{"Accept":"x"}` (order 20) | takes the quest on |
| `TurnInQuest` | `{"TurnIn":"x"}` (order 30) | hands it in, and reports the completion so the page can float a toast |

## Rules to keep

- **[`DialogueQuests`](DialogueQuests.java) is the whole of what this package may reach.** It carries a `QuestStateReader` (zc-progression's narrow READ seam), a `Subject` factory, an answer set, and the two write methods. **Never import `QuestEngine`** - it mutates, and a condition that could reach it could accept a quest while merely deciding whether to render an option. The compile error is the point.
- **Both write methods refuse by default.** A consumer that wires only the reader gets quest-aware LINES with no way for a conversation to change anything, and has to opt in before a dialogue can start or finish a quest. Unwired entirely (`DialogueQuests.NONE`), every condition reads NOT_STARTED and every action does nothing - a conversation written for a quest system this server does not run hides those beats instead of promising them.
- **The answer set is asked once per evaluation and every id is tried.** One character can stand for several quest-giver ids, and only the consumer knows how its ids fold together; the default is the literal id. A hand-in walks the set until one takes it, which is what lets a quest report back wherever its giver stands.
- **"Does this place have anything to OFFER" is answered by ASKING, never by guessing.** Which quests a place hands out is an authoring-layer association plus a gate pass, neither of which the quest runtime holds - so `HasOfferableQuests` reads zc-progression's [`quest/NpcOfferProviders`](../../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/quest/CLAUDE.md), the open table where each mod's catalogue and gates answer for themselves. One authored line then works on a server running two content mods, and neither had to know about the other. Only AVAILABLE offers count: a quest the player can see but not yet take is not something to hail them about.
- **It ships as a CLASS and is NOT in `types(...)`.** A dialogue `Type` id resolves to ONE class in a process-wide table, so a consumer already shipping its own `HasOfferableQuests` would find every installed mod's files decoding into whichever registered last. `offerableType(quests)` is what a consumer registers, deliberately, in the same change that drops its own. A consumer with no condition of its own can register it straight away.
- **`subjectOf(store, ref, playerRef, player)` is the seam for surfaces that are not conversations.** An NPC panel, a fourth party's page and a hand-in button have the player and the world but no dialogue context, and a consumer whose quest runtime needs a richer subject has to be reachable without one. **Override THAT one** when your runtime needs more than a plain player handle; `subject(ctx)` defaults to it and may keep enriching from whatever the conversation already fetched. Overriding only the context form leaves every non-dialogue surface with a bare subject your runtime may not accept.
- **A state name that is not a state hides the line** rather than quietly meaning NOT_STARTED, and the content audit reports it (`QUEST_STATE_UNKNOWN`). Same for a quest line with no id.
- **Behaviour is replaceable without touching authored files.** A consumer that wants richer accept / hand-in behaviour re-registers the same `Type` id with its own handler; the shape, the shorthand and every file stay as they are.
