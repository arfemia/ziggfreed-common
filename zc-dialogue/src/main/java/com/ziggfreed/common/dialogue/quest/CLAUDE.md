# dialogue/quest/ - the quest-aware conversation vocabulary + the completion hand-off

Router for `com.ziggfreed.common.dialogue.quest`. The lines every quest giver needs, shipped with the generic vocabulary rather than re-invented per mod: is this quest at that point yet, can it be handed in HERE, is anything ready to hand in here, take it on, hand it in. Plus the other end of a quest's life: what the giver says once it is done, and who puts that on the screen.

| Type | Authored as | Answers / does |
|---|---|---|
| `QuestState` | `{"Type":"QuestState","Quest":"x","State":"ACTIVE"}` or `"States":[...]` | the EFFECTIVE status, so a finished daily off cooldown re-offers itself |
| `ReadyToTurnIn` | `{"Type":"ReadyToTurnIn","Quest":"x"}` | can hand THIS one in here, in full, carrying what it asks for |
| `HasReadyToTurnIn` | `{"Type":"HasReadyToTurnIn"}` | can hand ANY quest in here (one line that never needs maintaining) |
| `HasOfferableQuests` | `{"Type":"HasOfferableQuests"}` | has anything the player could take on right now (SHIPPED UNREGISTERED, see below) |
| `AcceptQuest` | `{"Accept":"x"}` (order 20) | takes the quest on |
| `TurnInQuest` | `{"TurnIn":"x"}` (order 30) | hands it in, and reports the completion so the page can float a toast |

## The completion hand-off: one policy, many hosts

A quest may name the conversation its giver has once it settles (common's top-level
`CompletionDialogue` leaf). WHETHER that conversation plays is not a fact about the quest and not a
fact about whichever screen the player finished it on, so it is decided ONCE here and every UI
merely hosts the result.

| Class | What it is |
|---|---|
| [`QuestCompletionRouting`](QuestCompletionRouting.java) | THE policy. `decide(...)` answers, `handOff(...)` decides and drives |
| [`QuestHandOff`](QuestHandOff.java) | the answer: the conversation, the character, and `Outcome` = why |
| [`QuestDialogueHost`](QuestDialogueHost.java) | the seam a consumer UI registers: `knows` + `open` |
| [`QuestDialogueHosts`](QuestDialogueHosts.java) | the open table of them, mirroring `NpcOfferProviders` |
| [`QuestCompletionDialogueValidator`](QuestCompletionDialogueValidator.java) | the audit for a conversation nothing can open |

- **The policy, in order**: no conversation authored -> `NONE_AUTHORED`; nobody in front of the
  player -> `NO_NPC_CONTEXT`; nothing registered knows it -> `NO_HOST`; otherwise `PLAY`.
- **`NO_NPC_CONTEXT` is the rule this layer exists for.** A completion conversation is a conversation
  WITH somebody. A quest log, an objective book, an admin command and an auto-claim out in the field
  have nobody to speak the lines, and picking an NPC would put words in the mouth of a character the
  player is not standing at. Every one of those surfaces skips the beat identically without any of
  them knowing the rule, which is exactly what stops three UIs from improvising three answers.
- **`knows` and `open` are on ONE interface deliberately.** A decision that said PLAY on a
  conversation nothing could open would leave a caller that has already returned from its own refresh
  staring at a dead screen.
- **`open` returning false is a real answer, not a failure.** The caller still owes the player a
  response and keeps its own refresh; a host that declines does not stop the walk, because another
  mod may know the same id and be able to open it.
- **The routing deliberately does NOT check that the quest settled.** The caller fires it at the
  moment it owns, because only the caller knows which moment it just finished; this owns what
  FOLLOWS. A status re-read here would need a subject and would make the routing a second authority
  on completion, free to disagree with the first.
- **`QuestHandOff.Outcome` is a RESULT discriminator, never an authored mode.** Nothing in any JSON
  selects it and no codec reads it; a new constant would mean a new REASON a hand-off did not happen.
  Its javadoc says so, because the "orthogonal knobs, never modes" reflex would otherwise try to
  decompose it into booleans.
- **The validator lives HERE, not in the quest audit**, and calls the SAME `QuestDialogueHosts.knows`
  the runtime calls. The quest authoring layer sits below this module and can see no conversation
  store, so a check there could only be handed a probe - and a second probe is a second answer, free
  to drift from the one the game uses. WARNING severity, by the shared rule: the owning mod may
  register its host after the audit runs, or not be installed at all.
- **The at-NPC form is on the encounter.** `NpcEncounter.completionHandOff` / `playCompletion` route
  on the character's PRIMARY id (never whichever alias took the hand-in), so the conversation's
  `@self` targets and its header name the character the player is actually looking at. The
  conversation form keeps `playCompletion`'s false default on purpose: a conversation does not hand
  off to itself, and a `TurnIn` beat inside a dialogue routes onward with `Goto`.

## Rules to keep

- **[`DialogueQuests`](DialogueQuests.java) is the whole of what this package may reach.** It carries a `QuestStateReader` (zc-progression's narrow READ seam), a `Subject` factory, an answer set, and the two write methods. **Never import `QuestEngine`** - it mutates, and a condition that could reach it could accept a quest while merely deciding whether to render an option. The compile error is the point.
- **Both write methods refuse by default.** A consumer that wires only the reader gets quest-aware LINES with no way for a conversation to change anything, and has to opt in before a dialogue can start or finish a quest. Unwired entirely (`DialogueQuests.NONE`), every condition reads NOT_STARTED and every action does nothing - a conversation written for a quest system this server does not run hides those beats instead of promising them.
- **The answer set is asked once per evaluation and every id is tried.** One character can stand for several quest-giver ids, and only the consumer knows how its ids fold together; the default is the literal id. A hand-in walks the set until one takes it, which is what lets a quest report back wherever its giver stands.
- **"Does this place have anything to OFFER" is answered by ASKING, never by guessing.** Which quests a place hands out is an authoring-layer association plus a gate pass, neither of which the quest runtime holds - so `HasOfferableQuests` reads zc-progression's [`quest/NpcOfferProviders`](../../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/quest/CLAUDE.md), the open table where each mod's catalogue and gates answer for themselves. One authored line then works on a server running two content mods, and neither had to know about the other. Only AVAILABLE offers count: a quest the player can see but not yet take is not something to hail them about.
- **It ships as a CLASS and is NOT in `types(...)`.** A dialogue `Type` id resolves to ONE class in a process-wide table, so a consumer already shipping its own `HasOfferableQuests` would find every installed mod's files decoding into whichever registered last. `offerableType(quests)` is what a consumer registers, deliberately, in the same change that drops its own. A consumer with no condition of its own can register it straight away.
- **`subjectOf(store, ref, playerRef, player)` is the seam for surfaces that are not conversations.** An NPC panel, a fourth party's page and a hand-in button have the player and the world but no dialogue context, and a consumer whose quest runtime needs a richer subject has to be reachable without one. **Override THAT one** when your runtime needs more than a plain player handle; `subject(ctx)` defaults to it and may keep enriching from whatever the conversation already fetched. Overriding only the context form leaves every non-dialogue surface with a bare subject your runtime may not accept.
- **A state name that is not a state hides the line** rather than quietly meaning NOT_STARTED, and the content audit reports it (`QUEST_STATE_UNKNOWN`). Same for a quest line with no id.
- **Behaviour is replaceable without touching authored files.** A consumer that wants richer accept / hand-in behaviour re-registers the same `Type` id with its own handler; the shape, the shorthand and every file stay as they are.
- **`completionDialogueOf` is a READ of the consumer's catalogue, and the whole of what a consumer decides about the hand-off.** WHICH conversation a quest names is authored data only the consumer can reach; WHEN it plays is not a consumer decision and lives in `QuestCompletionRouting`. It defaults to null, so a mod that wires only the reader gets quest-aware lines and no hand-off. This is a widened seam rather than a new interface, per the quest module's own rule about default methods.

## Tests

`QuestCompletionRoutingTest` pins every way the beat is SKIPPED (nothing authored, nobody there, nothing installed that could open it), that hosts are walked in sorted id order so the winner survives a restart, that a throwing host costs only its own answer, and that a host declining to open leaves the caller its refresh. `QuestCompletionDialogueValidatorTest` pins the finding's severity, code, source and message. `NpcEncountersTest` pins the primary-id routing and the conversation-does-not-hand-off-to-itself default.

**A consumer's own host is SMOKE territory by design and carries no unit test.** A real host does two things a unit JVM cannot stand up: it probes a live dialogue store and it drives a page manager. Everything decidable is on this side of the seam and is pinned above; what is left for a host is one `knows` lookup and one page open, and a test double of either would only assert that the double works. Smoke it in game instead: hand a quest in at the giver and confirm the conversation opens with the completion toast riding over it, then hand the same quest in from a quest log and confirm it pays out and stays on the log.
