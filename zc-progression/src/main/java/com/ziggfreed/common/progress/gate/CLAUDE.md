# progress/gate/

- One `GateEvaluator` per server, held by `progress/runtime/ProgressionGates` over the runtime's live factor registry, factor context and gate kinds. `builder()` is only for a test or a genuinely private evaluator, whatever the setup example in `GateEvaluator`'s own javadoc says.
- Every unwired seam refuses; content that authors no requirements needs no wiring.
- A refusal is an opaque token, never a sentence. A collect-all `factor:` token carries `@<Param>`, and an `AnyOf` contributes exactly one token however many routes it offers.
- `passes` and `firstFailure` short-circuit, for per-row checks. `allFailures` and `allRefusals` walk every leaf (so they may call a `Custom` kind a short walk skips), and must agree with `firstFailure` on whether a block is shut.
- `Permission` is evaluated as the `hytale:permission` factor condition (one code path) but still refuses with the token `permission`.
- Nesting stops at one level, and `Not` takes clauses, never a nested `GateSpec`. A `Not` group shuts the gate by passing, so an empty one shuts the content for everyone and is reported as `BLANK_REQUIREMENT`.
- Four leaves (`Factors`, `Permission`, `Quests`, `Custom`); anything narrower is a registered `GateKind`. Leaf names are frozen because quests and achievements decode the same block.
- A completion prerequisite (the `Quests` leaf or `ziggfreedcommon:quest_completed`) means `COMPLETED`, never `COMPLETED_UNCLAIMED`. Gate a one-shot on that flag and a run count on `quest_completions`, which counts collections.
- `FeatureLift` moves only a top-level plain feature or `hytale:mod_installed` condition (bounds-less or `Min: 1`) onto the hide axis. A nested or upper-bounded one stays a lock, and a nested presence check needs an explicit `Min: 1`: both ids read a definite 0, which a bounds-less condition passes.
- The completion probe is set after `build()`, because the engine that answers it is built after its gates.
