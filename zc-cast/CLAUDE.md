# zc-cast

- Depends on `zc-core` only. Its library dependents use it for custom interaction Types: `zc-objectives` (the objective book, `ZigCreditProgress`, `ZigGrantReward`), `zc-effects` (`ZigCostume`, `ZigStun`, `ZigTaunt`, `ZigAggroDrop`, `ZigAggroSuppress`) and `zc-instance` (`ZigEnterDungeon`); `zc-presentation` uses `ModelParticleService` alone, for the item-cost confirm page's sparkle.
- Ship no content: a consumer supplies its own step, context and result types, on-hit builders and drain subclass. Never bake in an ability or interaction vocabulary.
- The public API is additive-only once frozen, because consumer mods compile against it. Anything that touches a `Store` or `Ref` runs on the world thread; the kernel and the targeting math stay pure.
