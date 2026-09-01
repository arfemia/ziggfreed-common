# dialogue/state/ - seen-ness, memories and the flag stores

Router for `com.ziggfreed.common.dialogue.state`: everything the engine remembers about a player
across renders, worlds and restarts. The authored semantics (`Once`, `Memories`, the key grammar,
`ResetWithQuest`) are described in full in the parent [`../CLAUDE.md`](../CLAUDE.md); this is the
map of what lives here.

- **[`DialogueOnce`](DialogueOnce.java)** - the authored seen-ness knob on a `Start` entry or an
  option (`true`, or `{"Where": {Match|GameplayConfig|ExcludeMatch}}`, the shared `WorldSelector`;
  the old `World` leaf is retired and refuses with a message naming `Where`); `keyFor` resolves the
  storage key for the player's current world.
- **[`DialogueMemory`](DialogueMemory.java)** - one declared memory's scope and lifetime
  (`Where` / `ResetWithQuest` / `Shared` / `Session`, all nullable and orthogonal; a retired
  `World` leaf refuses and names `Where`); `keyFor` resolves its storage key.
- **[`DialogueMemories`](DialogueMemories.java)** - THE store: routes each key to the session or
  persistent backend by declared lifetime, and honours `ResetWithQuest` itself off the quest
  engine's re-arm report (`SubjectHandles` is how a consumer's subject reaches a live player).
- **[`DialogueFlagStore`](DialogueFlagStore.java)** / **[`InMemoryDialogueFlagStore`](InMemoryDialogueFlagStore.java)** -
  the opaque has/set/clear seam and the shipped session backend.
- **[`DialogueStateKeys`](DialogueStateKeys.java)** + **[`DialogueFlagScope`](DialogueFlagScope.java)** -
  internal key plumbing: the composed shapes and the world-scope fold (keyed by a pattern's
  literal CORE, `ResetWithQuest` prefixing `q:<questId>:` onto the whole key).
- **[`DialogueWorlds`](DialogueWorlds.java)** - the one guarded current-world read the `World`
  condition and every scope resolution share.
