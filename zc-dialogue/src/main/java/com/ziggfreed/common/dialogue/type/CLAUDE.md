# dialogue/type/ - the action/condition contract

Router for `com.ziggfreed.common.dialogue.type`: what a mod REGISTERS into the shared engine, and
the dispatcher that runs it. The engine's whole story (one engine per server, first-wins claims,
ordering) is the parent [`../CLAUDE.md`](../CLAUDE.md); this is the map of what lives here.

- **[`DialogueAction`](DialogueAction.java)** - the `Type`-discriminated action base and the
  pre-seeded generics as nested classes (`Goto`, `Close`, `Remember`, `Forget`, `MarkTalked`, the
  carrier `OpenPage`), each with its own field `BuilderCodec`.
- **[`GenericActions`](GenericActions.java)** - what `DialogueEngine` seeds `Goto`/`Close`/
  `Remember`/`Forget`/`MarkTalked`/`OpenPage` FROM. Lives here (not on `DialogueEngine`) because its
  sugar lambdas construct those nested `DialogueAction` types directly, writing their
  package-private `node`/`memory`/`target` fields; `Remember`/`Forget` take the declared-memory-key
  lookup as an injected `BiFunction` rather than reaching back into `DialogueEngine` for it.
- **[`DialogueCondition`](DialogueCondition.java)** - the condition base and its generics
  (`Remembered`, `NotRemembered`, `World`, `Factor`, the `Combinator` family `AllOf`/`AnyOf`/`Not`).
- **[`CombinatorCodecs`](CombinatorCodecs.java)** - the combinators' DECODE arm (`AllOf`/`AnyOf`/
  `Not`, keyed `All`/`Any`/`Of`), registered into `DialogueTypeTable`'s `conditionCodec` from here so
  the write to `Combinator#children` stays inside this package. Their EVALUATORS stay on
  `DialogueEngine` (each walks its children back through that engine's own condition pass).
- **[`DialogueActionType`](DialogueActionType.java)** / **[`DialogueConditionType`](DialogueConditionType.java)** -
  ONE registration binding schema + behaviour + presentation + shorthand so they cannot drift
  (codec + handler/evaluator + optional style kind + optional sugar leaf).
- **[`DialogueActionHandler`](DialogueActionHandler.java)** / **[`DialogueConditionEvaluator`](DialogueConditionEvaluator.java)** -
  the behaviour interfaces a consumer implements.
- **[`DialogueActionExecutor`](DialogueActionExecutor.java)** - dispatches by handler MAP (never
  `instanceof`); `Mut` is the per-click outcome accumulator; `adoptHandler` is how the shared
  engine grows first-wins as each mod registers.
