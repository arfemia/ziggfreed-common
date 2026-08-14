# CLAUDE.md - `progress/gate/` (module `zc-progression`)

The ONE requirement model behind every gated thing in this module: what a `Requires` block may say,
and who answers it. Shared, so a requirement written on one kind of content means exactly what it
means on the next.

Package root `com.ziggfreed.common.progress.gate`.

| Class | What it is |
|---|---|
| `GateClause` (+ `appendLeaves`) | one group of requirements, ALL of which must pass: `Factors` / `Permission` / `Quests` / `Custom` |
| `GateSpec` | the whole `Requires` block: the leaves plus `AllOf` and `AnyOf` |
| `GateKind`, `GateKindRegistry` | the OPEN `Requires.Custom` vocabulary a consumer extends, either desugaring to factor conditions or answering directly |
| `GateEvaluator` | who answers a block for one subject, and the opaque reason token naming what shut it |
| `GateValidator` | the shared `Requires` audit every domain reports through (blank requirements, unknown factors / prerequisites / gate kinds), so a shop's gate and a quest's gate are checked by ONE pass |

## Rules to keep

- **Every unwired seam REFUSES.** A gate that cannot be evaluated must never open, or the first
  server missing a dependency hands out content the author gated. Content authoring no requirements
  needs no wiring at all and is open to everyone.
- **A leaf that has a factor spelling is EVALUATED as that factor**, never answered a second way
  beside it. `Permission` is the worked case: it reads as `hytale:permission` through the same
  registry lookup a `Factors` entry takes, so the two spellings cannot drift, and the only thing a
  consumer wires for it is the factor vocabulary it was going to wire anyway.
- **A refusal is a TOKEN, never a sentence** (`"factor:yourmod:rank"`, `"permission"`,
  `"quest:intro_1"`, `"gate:yourmod:reputation"`, `"any_of"`). Turning one into text a player reads
  is the consumer's job, because only the consumer knows the player's language and its own wording.
- **Nesting stops at one level.** `AllOf` plus `AnyOf` expresses "these, plus one of those", which is
  the shape real requirements take. A requirement that genuinely needs more is better written as a
  registered `Custom` kind whose rule lives in code.
- **Four leaves, and a new one is a high bar.** Anything narrower is a registered `GateKind`. Only a
  genuinely universal requirement earns a leaf, because a leaf is a field every author sees forever.
- **The completion probe is settable AFTER the build** on purpose: the usual answer comes from an
  engine that cannot exist until its gates already do, so the engine wires itself in afterwards.
- **Field names are frozen by sharing.** Both lifecycle engines decode the same `Requires` block, so
  a rename here is a rename in every authored file of both.

## Every leaf also has a FACTOR spelling, and both mean one thing

`Factors` is the only leaf a content type outside this module needs: content elsewhere in the
library carries factor conditions and nothing else, so the same requirement has to read identically
written either way. It does, deliberately - by being the same evaluation where that is possible, and
by a pinned agreement where it is not:

| leaf | the same requirement as a factor condition | how they meet |
|---|---|---|
| `Permission: "yourmod.vip"` | `{"Factor": "hytale:permission", "Param": "yourmod.vip", "Min": 1}` | ONE code path: the leaf is evaluated AS the condition |
| `Quests: ["intro_1"]` | `{"Factor": "ziggfreedcommon:quest_completed", "Param": "intro_1", "Min": 1}` | two paths, both over the STORED status, agreement pinned by a test |
| (no leaf) an earned achievement | `{"Factor": "ziggfreedcommon:achievement_earned", "Param": "first_blood", "Min": 1}` | factor only |
| (no leaf) a level | `{"Factor": "hytale:stat", "Param": "<a mirrored stat channel>", "Min": 30}` | factor only |

### `Permission` IS the factor, spelled short

What an author writes is unchanged - `"Permission": "yourmod.vip"` is still the whole leaf - but
there is no second answer behind it. The evaluator builds
`{"Factor": "hytale:permission", "Param": "<the node>", "Min": 1}` and resolves it through the very
registry every other factor condition goes through, so one permission question has one answer
wherever it is asked: a quest's `Requires`, a storefront's, an NPC placement gate, a dialogue
condition. What follows from that:

- **Nothing is wired for it.** Register the portable `hytale:` standard library into the registry you
  hand `GateEvaluator.Builder#factors`, give `factorContext` a context carrying the player's store
  and ref, and the leaf works. There is no probe to supply and none to forget.
- **It fails closed the way the factor does**, which is the same set of refusals as before: no
  vocabulary, nothing registered under `hytale:permission`, no live player behind the subject, a
  subject that is not a player at all, or a blank node. Any of them reads as "cannot tell", and
  cannot-tell never opens a gate.
- **The refusal still names the LEAF.** A `Permission` that shuts a gate reports `"permission"`, not
  `"factor:hytale:permission"`, because the token names what the author wrote and can go and fix.
- **A blank node asks for something and refuses**, rather than reading as an absent requirement.
  `"Permission": ""` names no node anybody can hold; leave the key out to ask for no permission.
- **The id is named as a plain string** (`GateEvaluator.PERMISSION_FACTOR`), never as a reference to
  the class that registers it: that class sits in a module above this one. Nothing is lost, because
  the id is resolved through the registry at evaluation time exactly as an authored id is - which is
  what makes this the same requirement rather than two that happen to agree.

Both completion routes still bottom out in `QuestLifecycle.isFinished` over the STORED status, and
those two ARE separate pieces of code, so their agreement is pinned by a test rather than assumed
(`ProgressionFactorsTest`). The permission pair needs no such pin for correctness and carries one
anyway (`QuestGateTest`), because the day somebody re-grows a second answer here is the day it should
fail the build.
