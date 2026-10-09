# dialogue/state/

- A `Once` or `Memories` scope keys by the matched axis's literal core (a `GameplayConfig` id, a pattern's core), so "already greeted here" survives an instance world rebuilt under a fresh name.
- The key wrap order is load-bearing: `ses:` outermost (it picks the backend), then `ResetWithQuest`'s `q:<questId>:`, then the world scope around only the final segment. The quest clear is a prefix match, so never reorder the segments.
- An option's `Once` is keyed by its `OnceId`, else its `LabelKey`, never its index. An entry's `Once` is spent when the beat completes, so leaving mid-beat shows it again.
- A `Once` with a `Period` files its key under the window it was spent in, `<key>:PD<epoch day>` or `<key>:PW<Monday week>` (`DialogueOnce.slotFor`, after the world scope), and a spend clears that line's earlier windows by the `<key>:P` prefix first, so a player keeps one key per periodic line. The window segment is the only upper-case segment a `once:` key carries, which keeps that prefix off every other key: never upper-case another segment.
- A `Once` with `PerCharacter: true` files its key under the character the conversation is with, `<key>:c:<character>` (`DialogueOnce.characterKey`, from `DialogueContext.contextId()`), after the world scope and before any window, so a window family stays one character's; unauthored, or with no character, it keeps the one shared key.
- `DialogueMemories` routes each key to the session or persistent backend by its declared lifetime (`Session`, else persistent) and is the only place `ResetWithQuest` is honoured.
- A bare `*` scope is no scope and a validator finding; the retired `World` leaf is refused with a message naming `Where`.
