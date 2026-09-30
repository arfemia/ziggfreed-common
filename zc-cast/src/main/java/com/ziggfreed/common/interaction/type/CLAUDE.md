# interaction/type/ - the custom interaction-Type toolkit

- Expose a Type's codec through a lazy static `codec()` method, never a `public static final CODEC` field: touching `Interaction.CODEC` throws in class init in any JVM whose log manager is not the engine's, the default `test` task and a consumer's test JVM included.
- Only `InteractionTypes.register`/`registerAll` call `codecSupplier().get()`, after their null and blank guards; call them once from `setup()` on a live server, before any asset decode.
- Resolve a gate miss with `skip()` (the chain continues); `failed()` suppresses the chained native step, so keep it for a hard error or a deliberate Failed branch.
- Run a `firstRun` body through `guard()` so every exit writes a state: a missing state leaves the client spinning.
- A Type that can resolve Failed returns `WaitForDataFrom.Server`. Inside a Selector hit fork, `owner(ctx)` is the caster and `target(ctx)` the swept entity.
