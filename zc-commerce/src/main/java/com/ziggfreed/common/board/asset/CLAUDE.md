# CLAUDE.md - `board/asset/` (module `zc-commerce`)

The AUTHORING layer over the board engine: how a board of rotating contracts is written as a file,
how one contract is, and what a content audit reports.

Store paths (registered ONCE by the root `asset/FrameworkAssetRegistrar`, common OWNS them):
- `Server/ZiggfreedCommon/Boards/<ns>/<Id>.json` -> `BoardAsset` -> `BoardConfig`, owner layer
  `mods/ziggfreedcommon/boards.json` (read by
  [`commerce/fold/CommerceOwnerLayers`](../../commerce/fold/CLAUDE.md) off the same load event,
  since an override has nothing to inherit from until the pack layer has landed)
- `Server/ZiggfreedCommon/Bounties/<ns>/<Id>.json` -> `BountyAsset` -> `BoardAssetStore`

The folder under a type root is plain organization; the FILE NAME is the id.

What the BOARD engine reads - a `BoardSpec` and a pool of `BountyRef`s - is folded out of these by
[`commerce/fold/`](../../commerce/fold/CLAUDE.md); what the PROGRESSION engine runs is
`toDefinition`, below.

## A contract IS a quest, and that is the whole design

`BountyAsset` reuses the quest schema's shared groups verbatim - `Text`, `Listing`, `Objectives`
(the same `QuestObjectiveAsset` under the same per-id merge), `Rewards`, `Requires`, `Meta` - plus the
ONE group only a contract has: `Boards`, a list of structured memberships. So everything an author
already knows about writing a quest applies, a surface that renders a quest renders a contract, and
the two can never drift apart into two spellings of "kill eight of them".

`toDefinition` folds one into a `QuestDefinition`, so the progression engine runs contracts and quests
through one lifecycle.

## The TYPE stamps the policy, and that is the other half

Four behaviours are NOT authorable, and each is a bug that would otherwise be one careless file away:

| stamped | why no file may author it |
|---|---|
| never auto-claim | a contract that paid out in the field would vanish from the board and take the reward with it |
| hidden from open listings | a contract is read at its board; listing it as an open quest is a second, wrong door |
| externally governed repeat, clocked from FINISHING | a private cooldown outliving a posting burns the next period's slot |
| collected at the accept SITE | any board of that id answers, and nowhere else does |

## The pieces

| Class | What it is |
|---|---|
| `BoardAsset` | one board: text, order, the shared `Rotation` / `Selection` / `Reroll` groups, its slots, the header wallets, the per-band `Grades` (what each band is called) and `AcceptRequires` (what each band takes to accept) maps, `Requires`, `Where` |
| `BoardSlotAsset` | one slot of a posting: the shared slot leaves plus `Difficulty` |
| `BountyAsset` (+ `.Listing`/`.BoardMembership`) | one contract, and the fold that stamps the policy |
| `BoardConfig` | the `defaults < pack < owner` fold, owner layer `mods/ziggfreedcommon/boards.json` |
| `BoardAssetStore` | the loaded contracts, and the fold into runnable definitions |
| `BoardValidator` | the content audit; shared `validation.Finding` values under domain `board` |
| [`commerce/asset`](../../commerce/asset/CLAUDE.md) | the groups shared with shops: `Cost`, `Rotation`, `Selection`, `Slot`, `Reroll` |
| [`progress/gate`](../../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/progress/gate/CLAUDE.md) | `Requires` and `AcceptRequires` values: the same block a quest carries, and the same audit |

## Rules to keep

- **`AcceptRequires` is a map of ordinary `Requires` blocks keyed by BAND**, and it merges per band
  under `Parent`, so a child board raises one band's bar and keeps the rest. The schema learns nothing
  about levels or classes: a gate is a factor bound like every other gate on the server, and a band
  can therefore be gated on anything a factor can read.
- **Checked at ACCEPT, never at posting.** A contract a player is not ready for is still POSTED and
  still shown, locked, so they can see what to work towards. Filtering it out of the draw would make
  the board read as empty for exactly the players it is meant to give a goal to.
- **`Boards` is a LIST**, so one contract hangs on several boards at a different band and weight on
  each. It is ONE leaf as far as inheritance goes: authoring it replaces the inherited list whole,
  which is how a child of a shared skeleton moves boards.
- **`Difficulty` is a free content word**, matched case-insensitively against a board's slots and used
  as the `AcceptRequires` key. Nothing here enumerates the bands; a pack invents its own ladder.
- **A band SAYS something, and the board's own `Grades` map is where that is written**, keyed by the
  band's own word - never a slot. Each value is the ordinary text group, and its `TitleKey` is what a
  contract's grade reads as wherever one is shown. The library ships a word for the common bands
  (training, easy, normal, hard, elite), so a board using those needs nothing; a band a pack invented
  reads as its own word until `Grades` names it. The ladder a screen reads is: this `Grades` entry,
  then the same key from a mod that ships it, then the library's own default, then the band itself -
  so an unnamed band is a readable word rather than a key on the screen. Living on the board rather
  than a slot means an UNSLOTTED board (one posting whatever it holds, with no `Slots` block at all)
  can name its bands too. Declaring a band (a `Slots[]` entry naming its `Difficulty`) and naming it
  (a `Grades` entry) are two different authored facts: a board may declare a band with no word of its
  own, and the ladder still resolves it through the lower rungs.
- **`Abstract` is the ONE field that must never inherit.** A child of a skeleton is a real contract.
- **An id is what a player's progress is filed under**, so renaming one starts that contract over -
  and two files landing on one id is reported, naming the id, because the loser takes its progress
  with it.
- **Display text is keys**, resolved by the player's own client. `TextArgs` is how one written line -
  "Bring down {0} of them" - serves a whole family.
- **A `$Comment` in any of these files is a TIP for the server owner or pack author.**

## Adding to it

- A new contract field: first ask whether it belongs on the shared quest groups instead, since a
  contract IS a quest; only what is genuinely about being POSTED belongs here.
- A new board knob: a leaf or a nested nullable group, `appendInherited`, documented for an author. A
  cadence or a strategy is registered rather than added as a leaf.
- A new finding: add it to `BoardValidator` with a stable code; unknown means warning, impossible
  means error.
- Tests are mechanics, structure, and invariants. Fixture content is author-owned; never assert
  numbers that belong to somebody's balance pass.

## What became impossible by construction

A mistyped membership label can no longer orphan a contract or skew a draw; a contract can no longer
auto-claim in the field and lose its reward to the board rotating; a board and a shelf can no longer
disagree about what a cadence means; and a per-band gate can no longer be a combat-level map one
domain understands and nothing else does.
