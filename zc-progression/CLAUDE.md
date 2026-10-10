# zc-progression

- Never add a zc-presentation or zc-world edge (zc-dialogue would be a cycle): a turn-in conversation, a page or a waypoint the engines need is a seam the wiring root or a consumer fills.
- A surface that only reads progression takes `QuestStateReader`, and a factor reading takes `ProgressionFactors`' narrow `Reads`, never a mutating engine.
- After changing a quest or achievement codec, run `:zc-progression:generateSchemaDocs` through hyMMO's lane (`lane.ps1 build -Dir <ziggfreed-common folder> -Tasks ':zc-progression:generateSchemaDocs'`) and commit `SCHEMA.md`; `SchemaDocDriftTest` fails the build on drift.
