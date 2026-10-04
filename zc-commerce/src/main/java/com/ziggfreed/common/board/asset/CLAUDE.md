# board/asset/ - authoring boards and contracts

A board is `BoardAsset` (`Server/ZiggfreedCommon/Boards/`), a contract is `BountyAsset` (`Server/ZiggfreedCommon/Bounties/`); the file name is the id.

- A contract IS a quest: `BountyAsset` reuses the quest schema's shared groups verbatim and folds into a `QuestDefinition` through `toDefinition`. Put a new contract field on the shared quest groups unless it is about being posted.
- `toDefinition` stamps four behaviours no file may author: hidden from open quest listings, a repeat governed from outside and clocked from finishing, collected at the accept site (any board of that id), and kept out of the quest log's slots.
- A contract's `Listing` takes only the presentation leaves (`appendPresentationLeaves`), never `Hidden` or `RequirePrerequisites`.
- `AcceptRequires` maps a band to an ordinary `Requires` block and merges per band under `Parent`, so a child board raises one band's bar and keeps the rest.
- `Boards` is a list an authored child replaces whole. `Difficulty` is a free word matched case-insensitively; nothing enumerates the bands.
- A band's name lives on the board's `Grades` map. A label reads that entry, then `board.grade.<band>` from any namespace that ships it (the library ships training, easy, normal, hard and elite), then the band word itself.
- `Abstract` is the one field that never inherits: a child of a skeleton is a real contract.
- An id is what a player's progress is filed under: renaming a file starts that contract over, and two files on one id are reported as `DUPLICATE_BOUNTY_ID`.
- A new board knob is a leaf or a nested nullable group wired `appendInherited`. A selection strategy is registered through `rotation/SelectionStrategies`, never added as a leaf.
- A board's `isAvailable()` is what `listed()`, `firstListedId()` and its engine view's `enabled()` read; its `lockRequires()` is the accept gate (`BoardAssetSpec.requires()`), and the board stays readable with every contract locked.
- A contract's `isAvailable()` is what the draw reads (`BountyAssetRef.enabled()`), and `toDefinition` lifts its feature exactly as `QuestAsset` does, so a contract hidden by a feature is never posted or taken; one already carried is finished from its board's Mine tab.
