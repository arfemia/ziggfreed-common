# command/

- Run an authored (server-owner) command line through `CommandRunner`, which dispatches it as the console. Call `util/CommandExecutor` directly only for a fixed command the mod composed itself.
- The engine's `/give` reads a count only from `--quantity=N`; a positional count silently gives one. `normalizeGive` fixes a line before it runs, and `readGive` is the one reader for previews, fit probes and validators.
- Placeholder substitution is map-driven and leaves an unknown key standing, so a typo shows in the command that ran.
- `CONSOLE` answering true only means the line was handed to `CommandManager.handleCommand`, which runs it asynchronously: an unknown or refused command still answers true, and only a synchronous throw answers false. Never treat it as proof a reward was delivered.
- `AbstractTargetPlayerCommand` is the one per-player verb walk for `/zigcommerce` and `/zigprogress`. Each family keeps its own package-private `TargetPlayerSubCommand` and its own refusal wording, and `executeAsync` stays non-final (`QuestGiveCommand` overrides it for `--everyone`).
- A `/zig*` admin family writes no permission check in a command body: the engine derives `<plugin base permission>.command.<family>[.<group>].<verb>` and refuses before the body runs, and a verb also needs its ancestors' nodes unless it sits in a permission group (`AbstractCommand.generatePermission` and `hasPermission`).
- Every sentence, verb description and argument description is a lang key (the engine resolves a description as a key), and a status or flag is a keyed fragment, never a bare `true`/`false`.
- Register one command per verb (`give`, `take` and `set` stay three), never a mode argument, so each verb gets its own node and help line.
- A per-player verb extends this package's `AbstractTargetPlayerCommand` (online players only), never the engine's same-named `basecommands.AbstractTargetPlayerCommand`, which demands an extra `<node>.other` permission before a sender may name another player.
