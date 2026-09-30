# objectives/command/ (`/zigprogress`)

- Before adding a verb, read `zc-core/src/main/java/com/ziggfreed/common/command/CLAUDE.md`: the rules every `/zig*` admin family shares (permission nodes, description keys, one command per verb, the online-only target walk) live there, and it does not load from this package.
- A verb takes its subjects from `ProgressionRuntime.subjects()`, per half (a consumer may own the quest store and not the achievement one), and runs each mutating call inside the registered `ProgressionCallScope`, or a claim pays out in silence.
- Show a runtime status through a key of its own (`ProgressAdminKeysTest` fails on a missing one); join a row's flags as keyed fragments with `Msg.cat`, never `Msg.join` or a bare true/false.
- Groups nest: a verb's help key and permission node carry its group (`desc.quest.reset`), and `accept`, `claim` and `abandon` stay separate registered verbs.
- `reload` republishes only the shared layer; a consumer's folded layer needs that consumer's own reload.
- `quest reset` wipes the completion record, `--quest=all` also sweeps the `q:` memory namespace, and `memory forget` is the total memory clear.
- `achievement reset` cannot release a server-first the player won.
