# dialogue/state/

- A `Once` or `Memories` scope keys by the matched axis's literal core (a `GameplayConfig` id, a pattern's core), so "already greeted here" survives an instance world rebuilt under a fresh name.
- The key wrap order is load-bearing: `ses:` outermost (it picks the backend), then `ResetWithQuest`'s `q:<questId>:`, then the world scope around only the final segment. The quest clear is a prefix match, so never reorder the segments.
- An option's `Once` is keyed by its `OnceId`, else its `LabelKey`, never its index. An entry's `Once` is spent when the beat completes, so leaving mid-beat shows it again.
- `DialogueMemories` routes each key to the session or persistent backend by its declared lifetime (`Session`, else persistent) and is the only place `ResetWithQuest` is honoured.
- A bare `*` scope is no scope and a validator finding; the retired `World` leaf is refused with a message naming `Where`.
