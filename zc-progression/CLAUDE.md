# zc-progression

- Never add a zc-presentation or zc-world edge (zc-dialogue would be a cycle): a turn-in conversation, a page or a waypoint the engines need is a seam the library fills by default from above (zc-objectives or the wiring root); a consumer only overrides it.
- A surface that only reads progression takes `QuestStateReader`, and a factor reading takes `ProgressionFactors`' narrow `Reads`, never a mutating engine.
- One quest engine, one achievement engine and one `GateEvaluator` per server, held by `progress/runtime/`: a mod registers its parts there and reads them back. `builder()` on any of them is only for a unit test or a genuinely private one.
- `QuestModuleAgnosticismTest` fails the build on a consumer word anywhere in this module's `src/main/java` (routers excluded), so examples use `yourmod:`.
- `STAT_THRESHOLD` names a state, so nothing fires it: the quest engine re-reads it at accept, in `selfHeal` and after a dispatch that moved the same quest, the achievement engine only in `selfHeal`. Never a poll, a sweep or a re-read on every dispatch, which would scan the whole catalogue.
- After changing a quest or achievement codec, run `:zc-progression:generateSchemaDocs` through the workspace's lane (from the root, `tools\lane\lane.ps1 build -Dir libs\ziggfreed-common -Tasks ':zc-progression:generateSchemaDocs'`) and commit `SCHEMA.md`; `SchemaDocDriftTest` fails the build on drift.
