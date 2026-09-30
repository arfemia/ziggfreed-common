# commerce/command/ - `/zigcommerce`

- Every verb reads and writes through the same catalogues, engines and `CommerceStore` the pages and payouts use. A consumer wanting its own spelling registers an alias that calls through, never a second implementation.
- Before adding a verb, read `zc-core/src/main/java/com/ziggfreed/common/command/CLAUDE.md`: the rules every `/zig*` admin family shares (permission nodes, description keys, one command per verb, which target-player base to extend) live there, and it does not load from this package.
- There is deliberately no "rotate this board now" verb: a rotation is a pure function of id, cadence and clock, with no stored schedule. `resetrerolls` is the admin move; the player's shelf returns to the shared draw.
- A listing walks the catalogue and asks the store per offer or pool, never the store to enumerate what it holds, so a database-backed store is only asked cheap questions.
- `CommerceAdminKeysTest` fails the build when a spoken key, a verb description or an argument description has no line in `ziggfreedcommon.commerce.admin.lang`; keys inside that file drop the `ziggfreedcommon.commerce.admin.` prefix its filename carries.
