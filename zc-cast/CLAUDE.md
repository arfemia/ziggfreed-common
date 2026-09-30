# zc-cast

The cast and interaction runtime: the step-dispatch kernel, hit resolution, targeting, the per-world tick partition and native interaction-chain composition.

- Depends on `zc-core` only. `zc-objectives` is its one library dependent, for the objective book item's custom interaction Type.
- Ship no content: a consumer supplies its own step, context and result types, on-hit builders and drain subclass. Never bake in an ability or interaction vocabulary.
- The public API is additive-only once frozen, because consumer mods compile against it. Anything that touches a `Store` or `Ref` runs on the world thread; the kernel and the targeting math stay pure.
