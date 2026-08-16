# command/ - running an AUTHORED command line

`zc-core`. `CommandRunner` is the zero-code integration surface a pack author gets when a reward, a
drop or a station grant needs to do something the mod has no schema for.

## Why one primitive rather than a helper per consumer

Three things have to happen to every authored command, and none is worth getting wrong twice:

1. **Placeholder substitution** (`substitute(raw, Map<String,String>)`) - MAP-DRIVEN, so the
   vocabulary belongs to the consumer. Common bakes in no key list: a station passes
   `station`/`action`/`cycles`, a level-up reward passes `level`/`skill`, and `player` is simply the
   key every consumer happens to share. An unknown key is LEFT STANDING rather than blanked, so a
   typo shows up in the command that ran instead of becoming an empty argument.
2. **The `/give` quantity fix** (`normalizeGive`) - the engine's give command reads a quantity ONLY
   from `--quantity=N`. A positional count parses as nothing and silently delivers ONE item, so
   `give Bob Wood_Planks 32` hands over a single plank with no error anywhere. The rewrite fires
   only on a `give` verb, only when no `--quantity` is already present, and only when a third
   POSITIONAL token parses as a positive integer (flags are skipped; `Block_Stone2` is not a count).
3. **A per-call guard** - one bad authored line reports itself and the next line still runs.

There is a fourth thing, and it is the same rule read backwards: **what a `give` line HANDS OVER**
(`readGive` -> `Give(target, itemId, quantity)`). Anything working out what a give means has to know
that the count lives in `--quantity=N`, and knowing it twice is how a preview starts promising a
different number than the payout delivers - so an inventory-fit probe, a preview icon and a
validator all read through this one method. It returns strings and an int, never an engine type, so
it works in a bare unit JVM and needs no item system in front of it; whoever wants an actual stack
builds one from the three fields. It is deliberately LENIENT about a positional count (the author's
intent) where `normalizeGive` is about the line the engine will act on - normalize when you are
about to run it, read when you are about to describe it.

## API

- `run(raw, placeholders, failureSink)` / `runAll(raws, ...)` - resolve then dispatch AS THE SERVER
  CONSOLE. An authored command is server-owner content, so it must not be limited to what the
  player who happened to trigger it may do.
- `runWith(dispatcher, ...)` / `runAllWith(...)` - the same through a supplied `Dispatcher`.
- `resolveAll(raws, placeholders)` - every line as it WOULD be dispatched, running nothing (for a
  validator or an in-game preview).
- `readGive(line)` -> `Give(target, itemId, quantity)` or `null` - what a give line hands over, for
  a probe / preview / validator. Null for anything that is not a give or that names no item; an
  unreadable count costs the count and leaves the reading at one, never the whole line.
- `CommandRunner.CONSOLE` - the default dispatcher, over `util.CommandExecutor`.

`Dispatcher` is a one-method seam (`boolean dispatch(String) throws Exception`) so the whole class is
unit-testable with no live server, including the throwing-dispatcher case. **The boolean IS the
contract**: answer true only when the line genuinely reached the command system. A false answer reads
exactly like a throw - reported to the failure sink, and `runWith` answers false - because that is how
the console reports a refusal (`util.CommandExecutor.executeAsConsole` returns false, it does not
throw), and a caller told "it ran" pays out a reward no command ever delivered. A dispatcher with
nothing to check (it records the line, or hands it somewhere that cannot answer) returns true and says
in a comment why that is honest. Failures go to a caller-supplied `Consumer<String>` rather than a
logger, so a consumer routes them into its own guarded log seam, a validation report, or a test list.
A sink that itself throws costs its own line, never the grant loop.

## Relationship to `util/CommandExecutor`

`util.CommandExecutor` is the bare engine dispatch (`CommandManager.handleCommand` as console or as
a player) and stays as-is. `CommandRunner` is the layer ABOVE it that a content path should call:
resolve, normalize, guard, report. Reach for `CommandExecutor` directly only when running a fixed
command the mod itself composed.

World-thread, like the engine command manager underneath.
