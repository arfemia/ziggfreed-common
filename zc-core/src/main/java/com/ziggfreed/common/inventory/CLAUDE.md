# inventory/

- `DisposableItemMetadata` is the one server-wide list of item-metadata keys declared safe to destroy with their item. Declare a key once at setup (`declare`); a stat stamper's `Stamper.metadataKeys()` reaches it through `StamperRegistry.register`. Keys match exactly and a declaration is never retracted; a declared key is disposable whoever wrote it, so data that must survive lives under a key its owner never declares. What to do about an undeclared key (refuse, ask) is the consumer's policy. It sits in zc-core because zc-loot must reach it.
- No unit test runs a `PlayerAccess` accessor body (a unit JVM has no `Player`): after changing one, smoke an item pickup, a block break, a turn-in hand-in and a station Consume step in game.
