# zc-core

- `LibraryOwner.NAME` (the hyphenated `ziggfreed-common`) is an attribution for logs and admin output, never a lookup key. The per-domain `OWNER` constants carry the unhyphenated `ziggfreedcommon` ids (registry keys, `mods/ziggfreedcommon/`, `Server/ZiggfreedCommon/`).
- `test` runs untagged tests with no log manager, which is where this module's logging guards run (`SafeLogTest` asserts it); the `FactorContextTest` cases carrying a real `ItemStack` are tagged `engine-items` and run in `engineItemTest`.
- Code here is the most likely to run in a bare unit-test JVM with no Hytale bootstrap, so keep every engine-touching try-guard real.
- The one engine asset-store swap for tests lives in this module's `testFixtures` source set (`testing/EngineAssetStores`: `emptyItems`, `qualities`, `statChannels`). Any module's test reaches it through `testImplementation(testFixtures(project(':zc-core')))`, tagged `engine-items`; never copy it into a test. It never ships: the root jar bundles each module's main variant only.
