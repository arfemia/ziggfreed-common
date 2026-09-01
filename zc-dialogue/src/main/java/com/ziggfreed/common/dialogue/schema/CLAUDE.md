# dialogue/schema/ - the conversation model and its codec assembly

Router for `com.ziggfreed.common.dialogue.schema`: what a conversation IS once decoded, and the
one place its codec graph is assembled. Deliberately one FAT package: `DialogueTypeTable` reaches
package-private internals of `DialogueOption`, `DialogueStart` and `DialogueSugarValues`, and
`DialogueOption` reaches `DialogueSugar`'s fold, so splitting the cluster would force those
internals public. The engine's whole story is the parent [`../CLAUDE.md`](../CLAUDE.md).

- **[`NpcDialogue`](NpcDialogue.java)** - the decoded conversation (id, nodes, start, memories,
  fragments; `spliceFragments` is the post-decode shared-group fold).
- **[`DialogueNode`](DialogueNode.java)** / **[`DialogueOption`](DialogueOption.java)** - a screen
  and its rows; a node self-gates with `Conditions`, an option folds its shorthand into
  `getActions()`.
- **[`DialogueStart`](DialogueStart.java)** - the declared `{First, Quests, Then, Fallback}`
  sections; the ladder order is the engine's, fixed.
- **[`DialogueTypeTable`](DialogueTypeTable.java)** - the process-wide table of every registered
  `Type` and shorthand key; assembles the whole codec graph once, re-assembles on late
  registration with one warning.
- **[`DialogueSugar`](DialogueSugar.java)** / **[`DialogueSugarLeaf`](DialogueSugarLeaf.java)** /
  **[`DialogueSugarValues`](DialogueSugarValues.java)** - option shorthand as part of the schema:
  a leaf declares key + fold order + codec + factory, registered with its action.
- **[`DialogueChrome`](DialogueChrome.java)** / **[`DialogueHeaders`](DialogueHeaders.java)** -
  the per-character page chrome override and the additive header-note vocabulary.
- **[`DialogueFragmentConfig`](DialogueFragmentConfig.java)** - the server-wide shared option
  groups (`Server/ZiggfreedCommon/DialogueFragments/`), looked up after a conversation's own.
- **[`DeferredCodec`](DeferredCodec.java)** - resolves a conversation field's codec out of the
  table at first read, forwarding the `InheritCodec` merge question to its delegate.
