# commerce/command/ - the admin surface (module `zc-commerce`)

Router for `com.ziggfreed.common.commerce.command`. `/zigcommerce`: what this server's economy is
made of, what one player has, and the few things an admin puts right by hand.

**The module that owns an engine owns the commands that drive it.** Every verb here reads and writes
through the same catalogues, the same currency engine and the same state store the pages and the
payouts use, so a consumer wanting its own spelling registers an alias that calls through rather than
a second implementation that can disagree with this one. This is the library's FIRST command surface;
what it settles below is meant to be copied by the next one.

| Class | What it is |
|---|---|
| [`ZigCommerceCommand`](ZigCommerceCommand.java) | the family, and the one place its verbs are listed |
| [`CommerceCommandLine`](CommerceCommandLine.java) | the names, and the replayable `give` line. A LEAF: it imports nothing |
| [`CommerceAdminMessages`](CommerceAdminMessages.java) | every line this family says, and the two rules it says them by |
| [`TargetPlayerSubCommand`](TargetPlayerSubCommand.java) | the shared half of a per-player verb: name a player, be on their world thread, hold a `Subject` |
| [`CommerceValidateCommand`](CommerceValidateCommand.java) | `validate`, over `commerce/fold/CommerceAudit` |
| [`CommerceWalletsCommand`](CommerceWalletsCommand.java) | `wallets` |
| [`CommerceShopsCommand`](CommerceShopsCommand.java) | `shops [--shop=<id>]` |
| [`CommerceBoardsCommand`](CommerceBoardsCommand.java) | `boards [--board=<id>]` |
| [`CommerceShowCommand`](CommerceShowCommand.java) | `show [--player=<name>]` |
| [`CommerceWalletCommand`](CommerceWalletCommand.java) | `give` / `take` / `set`, three registered verbs from one class |
| [`CommerceResetCommand`](CommerceResetCommand.java) | `resetlimits` / `resetrerolls`, on the same terms |

## Rules to keep

- **No permission check is written anywhere here, on purpose.** The engine derives one node per
  command from the plugin and the command name, registers it, and refuses the call before a body
  runs: `ziggfreed.ziggfreedcommon.command.zigcommerce` for the family and
  `...zigcommerce.<verb>` per verb, with a sub-command needing BOTH. Nobody holds either until a
  server grants it, and the console holds everything. A second check inside a body would be a second
  vocabulary an owner has to discover, and the first one to drift.
- **A sentence is a KEY; an id or a number is a raw argument.** Ids and counts read the same in every
  language, and a translated id stops naming the thing an author has to go and edit. Keys live in
  `Server/Languages/<locale>/ziggfreedcommon.commerce.admin.lang`, so an in-file key drops the
  `ziggfreedcommon.commerce.admin.` segment the filename carries.
- **A command DESCRIPTION is a key too**, and so is an argument's. The engine resolves both through
  its own localization module, so a plain English description shows the reader raw text nobody
  translated. `CommerceAdminKeysTest` fails the build on a verb without one.
- **Never ship a bare `true`/`false` at a reader.** Two keys, chosen by the flag, beats one key with
  an untranslated word substituted into it. A disabled offer and a paid reroll each read as their own
  line for that reason.
- **A verb NAMES exactly one thing.** `give`/`take`/`set` and the two resets share an implementation
  and stay three and two registered commands, because that is how the engine's own families read and
  how each one gets its own node and its own help line. A mode argument would collapse them into
  something nobody can guess from `/help`.
- **The per-player verbs need the player ONLINE**, and say so. Commerce state lives on the player's
  own entity, so an offline edit has nowhere to land that a later login would read back.
- **This family does not extend the engine's own target-player base**, though it is otherwise the
  same shape. That base demands a SECOND node - `hytale.command.<this command's own node>.other` -
  before a sender may name anybody but themselves, which for an admin family is a node nobody would
  think to grant and a refusal nobody could explain.
- **There is deliberately no "rotate this board now".** What a rotating pool shows is a pure function
  of its id, its cadence and the clock, with no stored schedule anywhere - which is what makes every
  player see the same shelf and a restart show what was there before. Clearing a player's rerolls is
  the real admin move underneath the wish: their shelf goes back to the shared draw and their
  allowance for the period comes back.
- **A listing asks the CATALOGUE and then asks the store about it**, never the store for everything
  it holds. The seam answers per offer and per pool deliberately, so a database-backed implementation
  is never handed a question it cannot answer cheaply.

## Tests

`CommerceAdminKeysTest` walks the package and pins the failure this surface cannot have: a key with
nothing to resolve it from. It discovers the sources rather than listing them, so a verb added later
is covered without anybody remembering. It also pins the replayable line's exact spelling, which the
`Currency` reward kind builds and this family parses.

The commands themselves need a booted server, so what they DO belongs to in-game smoke. What they
decide is either one call into an engine that has its own tests, or the message rules above.
