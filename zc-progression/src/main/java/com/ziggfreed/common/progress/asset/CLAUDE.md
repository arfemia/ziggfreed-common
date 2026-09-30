# progress/asset/

- A leaf here reaches both engines at once; a leaf only one engine wants belongs in that engine's own codec.
- Extend a group through its `appendLeaves`; never copy its leaf declarations into a second codec.
- Every leaf is `appendInherited`. Content inherits through native `Parent`, and `Abstract` alone is a plain `append` in every content codec, so a child of a skeleton is real content.
- `QualifierMatchMode` is the qualifier's own comparison (`EXACT` / `CONTAINS` / `PREFIX`, the words `MatchMode` gives the target), defaulting to `EXACT` so files authored before it keep their meaning. It rides `ObjectiveDef.qualifierMatchMode()` into `ObjectiveMatch.qualifierMatches(authored, mode, event)`.
- Never import `quest/` or `achievement/` from this package.
- `ContentTextAsset` (`Text`) lives in zc-core's `text/`, because a module below this one needs it.
- `ObjectiveLeafAsset.toDefBuilder` is the one place a kind alias is applied.
- An editor dropdown is authoring convenience, never validation: a hand-written file never passes through the editor.
- A consumer's own knob goes in `Meta` (`ContentMeta`), inherited per namespace with the block replaced whole; never argue it into a shared leaf.
- `GeneratorCore` is the one expander for every content type that writes a family from one file. It merges nothing (each generated body is an ordinary child carrying `Parent`); a value that is exactly one token keeps that token's type; an unbound token is an error that skips the entry.
