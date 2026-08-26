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
| `ContentListingAsset` (+ `appendLeaves` / `appendPresentationLeaves` / `appendVisibilityLeaves`) | `Listing`: the five presentation leaves (`Category` / `SortOrder` / `Tags` / `Chains` / `Icon`) plus the two visibility ones (`Hidden` / `RequirePrerequisites`); a type whose visibility is its own policy (a bounty contract) appends only the presentation five, so no visibility leaf exists there to decode and do nothing |
| `ContentRewardsAsset` | `Rewards`: the `{Auto, Claim}` two-bucket group every paying content type carries (quests, achievements, milestones, bounty contracts) - `Auto` lands when the content settles, `Claim` waits to be collected |
| `RewardEntryAsset` | one reward entry (`Kind` plus an open `Params` map), the element type of both `Rewards` buckets |
| `ProgressEditorDataSets` | the in-game editor pick lists (`objective_kinds`, `reward_kinds`) both engines' `Kind` fields point at |
| `GeneratorAxisAsset` | one `ForEach` axis: `Token` / `Values` / `Source` / `Filter` |
| `GeneratorSpec` | the four leaves the expander reads off ANY generator asset: `Base`, `IdPattern`, the axes, `Child` |
| `GeneratedBody` | one piece of content a generator wrote: id, body carrying its `Parent`, base id, generator id |
| `GeneratorCore` (+ `.Expansion`, `.AxisValueSource`) | THE expander: the axis walk, the substitution contract, the findings |

## One expander, however many content types write families

A generator is not a quest feature: a shop offer, and whatever writes families next, want the same
"one file, one family" shape. So the walk, the substitution rules and the findings live here ONCE and
each store supplies only its own body type and the word a finding is phrased in. Two copies would
mean an author discovering that one generator substitutes an object KEY and the other does not, which
is the sort of difference nobody finds until a file silently writes the wrong thing.

- **Substitution, in full**: every string value, every object KEY, and `IdPattern`; a value that is
  EXACTLY one token keeps that token's own type (so `"Amount": "{amount}"` lands as a number); a token
  nothing binds is an ERROR and that one entry is skipped rather than shipped half-written.
- **`AxisValueSource` is a SEAM, not a registry.** The open value-source vocabulary a consumer
  registers is registered ONCE and adapted into this shape by each store, so a list a mod enumerates
  serves every generator on the server rather than being registered again per content type.
- **`domain` and `noun` are parameters** so a finding lands in the right report and reads in the
  author's own terms. Nothing here names a lifecycle or a content type.

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
