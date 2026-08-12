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

## Rules to keep

- **Every unwired seam REFUSES.** A gate that cannot be evaluated must never open, or the first
  server missing a dependency hands out content the author gated. Content authoring no requirements
  needs no wiring at all and is open to everyone.
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
