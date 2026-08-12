# CLAUDE.md - `progress/asset/` (module `zc-progression`)

The AUTHORING groups both lifecycle engines share. Every class here exists because two engines would
otherwise declare the same fields twice and drift apart one edit at a time.

Package root `com.ziggfreed.common.progress.asset`. Imports zc-core and `../` only - never
`../../quest/` or `../../achievement/`; a group that only one engine can have belongs to that
engine's own codec.

| Class | The group it declares |
|---|---|
| `ContentTextAsset` | `Text`: `TitleKey` / `FlavorKey` / `DisplayName` |
| `ContentMeta` | `Meta`: the namespace -> verbatim-block map both engines carry, plus the `decode` seam a consumer reads its own namespace through |
| `ObjectiveLeafAsset` (+ `appendLeaves`) | the seven leaves every authored objective carries: `Kind` / `Target` / `MatchMode` / `Qualifier` / `Amount` / `Zone` / `TextKey` |
| `RewardEntryAsset` | one `Rewards[]` entry: `Kind` plus an open `Params` map |
| `ProgressEditorDataSets` | the in-game editor pick lists (`objective_kinds`, `reward_kinds`) both engines' `Kind` fields point at |

## Rules to keep

- **Adding a leaf here adds it to BOTH engines at once.** That is the point, and it is also the
  check: if a proposed leaf only makes sense for one of them, it is not shared and belongs there
  instead (a quest's `Order` and hand-in place are the worked example).
- **`appendLeaves` is the extension mechanism**, mirroring the gate clause's: an engine's codec
  starts from that call and appends its own leaves. Never copy the leaf declarations into a second
  codec - the copy is exactly the drift this package prevents.
- **Every leaf is `appendInherited`**, so content with a `Parent` retunes one number and keeps the
  rest.
- **A dropdown is authoring convenience, never validation.** A hand-written JSON file never passes
  through the editor, so a content validator stays the real check and a free-typed id still works.
- **No consumer vocabulary**, same as the rest of the module: the agnosticism test scans here.
- **A consumer knob goes in `Meta`, never in a leaf here.** `ContentMeta` is the sanctioned escape
  hatch precisely so a mod-specific field (a ladder, a follow-up conversation, a feature toggle) never
  has to be argued into a shared codec. Inheritance is per NAMESPACE, block replaced whole: no deep
  merge, because the block has no schema at this level and a merge would have to guess.
