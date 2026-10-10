# zc-cast

- Depends on `zc-core` only. Its library dependents use it for custom interaction Types: `zc-objectives` (the objective book, `ZigCreditProgress`, `ZigGrantReward`) and `zc-effects` (`ZigCostume`).
- Ship no content: a consumer supplies its own step, context and result types, on-hit builders and drain subclass. Never bake in an ability or interaction vocabulary.
- The public API is additive-only once frozen, because consumer mods compile against it. Anything that touches a `Store` or `Ref` runs on the world thread; the kernel and the targeting math stay pure.
