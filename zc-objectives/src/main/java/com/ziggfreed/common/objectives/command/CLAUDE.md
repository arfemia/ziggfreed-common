# objectives/command/ - the admin surface (module `zc-objectives`)

Router for `com.ziggfreed.common.objectives.command`. `/zigprogress`: what the shared progression
runtime holds, where one player stands, and the few things an admin puts right by hand.

**The module that owns an engine owns the commands that drive it.** Every verb here reads and writes
through THE shared runtime - the one quest engine and the one achievement engine, the subject the
REGISTERED stores understand, the registered call scope around every mutating call - so a consumer
wanting its own spelling registers an alias that calls the same engine rather than a second
implementation that can disagree with this one. It is the library's second command family and copies
the first (`commerce/command/`, `/zigcommerce`) rule for rule; where the two differ it is because a
progression verb acts on content the runtime merges from several mods.

| Class | What it is |
|---|---|
| [`ZigProgressCommand`](ZigProgressCommand.java) | the family, its three groups, and the one place every verb is listed |
| [`ProgressCommandLine`](ProgressCommandLine.java) | the names: family, groups, verbs. A LEAF: it imports nothing |
| [`ProgressAdminMessages`](ProgressAdminMessages.java) | every line this family says, the rules it says them by, and the words for the runtime's statuses |
| [`TargetPlayerSubCommand`](TargetPlayerSubCommand.java) | this family's fill of zc-core's shared [`AbstractTargetPlayerCommand`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/command/CLAUDE.md) walk: keeps the resolved `(store, ref, playerRef)` handles as a `Target` rather than building a `Subject` here |
| [`ContentArgs`](ContentArgs.java) | the `--quest` / `--achievement` argument read against the shared catalogue, with one spelling of each refusal |
| [`ProgressReloadCommand`](ProgressReloadCommand.java) | `reload`, over `ProgressionDefaults.publishAssetContent` |
| [`QuestListCommand`](QuestListCommand.java) | `quest list [--tag=<tag>]` |
| [`QuestGiveCommand`](QuestGiveCommand.java) | `quest give --quest=<id> [--player=<name>\|--everyone]` |
| [`QuestResetCommand`](QuestResetCommand.java) | `quest reset --quest=<id\|all> [--player=<name>]` |
| [`QuestCompleteCommand`](QuestCompleteCommand.java) | `quest complete --quest=<id> [--player=<name>]` |
| [`QuestStatusCommand`](QuestStatusCommand.java) | `quest status [--quest=<id>] [--player=<name>]` |
| [`QuestLogCommand`](QuestLogCommand.java) | `quest accept` / `claim` / `abandon`, three registered verbs from one class |
| [`AchievementListCommand`](AchievementListCommand.java) | `achievement list [--tag=<tag>]` |
| [`AchievementStatusCommand`](AchievementStatusCommand.java) | `achievement status [--achievement=<id>] [--player=<name>]` |
| [`AchievementGiveCommand`](AchievementGiveCommand.java) | `achievement give --achievement=<id> [--player=<name>]`, the force-it verb (same word, same meaning as `quest give`) |
| [`AchievementClaimCommand`](AchievementClaimCommand.java) | `achievement claim --achievement=<id|all> [--player=<name>]`, the peer of `quest claim` |
| [`AchievementResetCommand`](AchievementResetCommand.java) | `achievement reset --achievement=<id|all> [--player=<name>]`, one-or-all like `quest reset` |
| [`MemoryForgetCommand`](MemoryForgetCommand.java) | `memory forget [--player=<name>]`, over `DialogueMemories.forgetAll` |

## Rules to keep

- **No permission check is written anywhere here, on purpose.** The engine derives one node per
  command from the plugin and the command name, registers it, and refuses the call before a body
  runs: `ziggfreed.ziggfreedcommon.command.zigprogress` for the family, `...zigprogress.<group>` for
  a group and `...zigprogress.<group>.<verb>` per verb, a verb needing all of its ancestors. Nobody
  holds any of them until a server grants it, and the console holds everything. A second check
  inside a body would be a second vocabulary an owner has to discover, and the first one to drift.
- **The groups are NESTED collections, and the nesting is the naming.** `quest reset` and
  `achievement reset` are two things with one verb; a flat family would have invented `questreset`.
  A verb's help key carries its group (`desc.quest.reset`), and so does its node.
- **The subject comes from `ProgressionRuntime.subjects()`, never from here.** A store reaches a
  player's state through the handle its own owner attached, so a subject built anywhere else reads
  neutral and drops every write. `TargetPlayerSubCommand.Target` asks the runtime, per half (the
  quest subject and the achievement subject are asked separately, because a consumer may own one
  store and not the other).
- **Every mutating call runs inside the registered `ProgressionCallScope`.** That is what makes a
  claim from here fire exactly what the owning mod's own menu would - its toast, its follow-on
  grants, its bookkeeping. A verb that reached the engine directly would pay out in silence.
- **A sentence is a KEY; an id, a name or a number is a raw argument; a STATUS is a key too.** The
  runtime answers in `QuestStatus` / `AchievementStatus`, and shipping the constant at a reader would
  ship an untranslated token, so `ProgressAdminMessages.questStatus` / `achievementStatus` name each
  through a key of its own, and `ProgressAdminKeysTest` fails on a constant with none. What a piece
  of content is CALLED is a nested `Message` from the runtime's registered text sources
  (`questName` / `achievementName`), never a string resolved here. Keys live in
  `Server/Languages/<locale>/ziggfreedcommon.progression.admin.lang`, so an in-file key drops the
  `ziggfreedcommon.progression.admin.` segment the filename carries.
- **A row's flags are one nested argument built with `Msg.cat`** (`ProgressAdminMessages.flags`),
  each flag a keyed fragment: a bare `join` renders blank as a nested param, and a bare
  `true`/`false` is a word nobody translated. Absent flags contribute nothing.
- **A verb NAMES exactly one thing**, and **the two groups conjugate the same way**: `give` is
  the force-it verb on both, `claim` collects what waits on both, `reset` takes `<id|all>` on
  both, `status` takes the same one-id filter on both. `accept`/`claim`/`abandon` share an
  implementation and stay separate registered commands, because that is how the engine's own
  families read and how each gets its own node and its own help line.
- **The per-player verbs need the player ONLINE**, and say so. Progress lives on the player's own
  entity. This is also why `quest complete` has no offline form: a reward owed to somebody away
  needs a spool keyed by an identity (a directory of everyone who has ever connected) the runtime
  does not keep, so a consumer with one offers that through its own alias.
- **`reload` republishes the SHARED layer and says so.** Content a consumer folds from its own
  format is that consumer's layer, published under its own name, and only its own reload can re-read
  it. The counts printed are the merged catalogue's.
- **`quest reset` is a WIPE, `quest reset --quest=all` also sweeps the `q:` memory namespace, and
  neither is the total memory clear.** The engine's `wipeQuest` drops the completion record an
  in-play re-arm keeps and still reports the re-arm through `QuestResets`; the namespace sweep
  (`DialogueMemories.forgetAllQuests`) reaches a memory about a quest the player never took, which a
  per-id report cannot. A greeting a character remembers is not quest progress: `memory forget`
  is the verb that means all of it, which is why it is a group of its own rather than a quest verb.
- **`achievement reset` cannot release a server-first this player WON**, because `FirstClaimStore`
  records a winner and offers no release; when a consumer's durable table is installed and the
  player held one, the verb says so once. Whoever installed the table releases through its own
  command.
- **This family does not extend the engine's own target-player base**, for the same reason the
  commerce family does not: that base demands a second `hytale.command.<node>.other` node before a
  sender may name anybody but themselves.

## Tests

`ProgressAdminKeysTest` walks the package and pins the failure this surface cannot have: a key with
nothing to resolve it from. It discovers the sources rather than listing them, so a verb added later
is covered without anybody remembering; it reflects `ProgressCommandLine` (top-level verbs and every
nested group) for the help lines, and both status enums for the status words.

What the verbs DO is one call each into an engine that has its own tests: `wipeQuest` /
`wipeAllQuests` are pinned in zc-progression's `QuestResetsTest`, `resetAll` in
`AchievementEngineTest`. The commands themselves need a booted server, so what they do end to end
belongs to in-game smoke.
