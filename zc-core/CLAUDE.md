# CLAUDE.md - zc-core

The foundation module. The shared logger handle, JSON/codec leaves, the client-resolved `Msg`
factory, the generic keyed-asset store bases, and the inventory/health engine wrappers, plus the
small primitive-floor packages admitted here because two or more other modules need them and they
carry no domain vocabulary (see the root router's "zc-core admission rule" table for the full
per-package justification). It depends on nothing but the Hytale server jar, so every other module
in the library can rest on it without risk of a cycle.

## Build

Part of the twelve-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-core`). Not independently loadable: it produces a compile-time jar with no
`manifest.json`, merged into the single `ZiggfreedCommon-<version>.jar` by the wiring root. See the
root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build + install commands.

## Dependencies

- **Depends on**: nothing but the Hytale server jar (`compileOnly`) and `jsr305` (`api`, so the
  `@Nonnull`/`@Nullable` annotations resolve for every consumer compiling against the aggregate).
- **Depended on by**: every other module (`zc-cast`, `zc-dialogue`, `zc-effects`, `zc-entity`,
  `zc-instance`, `zc-loot`, `zc-objectives`, `zc-presentation`, `zc-progression`, `zc-world`) plus
  the wiring root. This is the bottom of the graph; nothing here may import anything above it.
- **Reverse-edge trap**: none possible in the other direction (there is nothing below zc-core to
  import), but an edge added FROM here to any other module is itself the violation - it would make
  the one module every other module rests on reachable only after building something above it.

## Packages

- [`asset/`](src/main/java/com/ziggfreed/common/asset/CLAUDE.md) - the framework asset-store bases
  (`AbstractKeyedAssetConfig`, `AbstractRawJsonAsset`, `AssetStoreRegistrar`/`AssetMergeAdapter`,
  `NestedAssetId`, `EditorDataSets`). The root's own `FrameworkAssetRegistrar` lives in the wiring
  root, not here, because it reaches into every domain.
- [`codec/`](src/main/java/com/ziggfreed/common/codec/CLAUDE.md) - `InheritMapCodec`,
  `JsonParentResolver`, `JsonTreeCodec`, the shared authorable leaves `Vec3`/`Rotation`/`TagMatch`.
- [`command/`](src/main/java/com/ziggfreed/common/command/CLAUDE.md) - `CommandRunner`, the
  authored-command primitive (map-driven placeholders, the `/give --quantity` fix).
- [`counter/`](src/main/java/com/ziggfreed/common/counter/CLAUDE.md) - `CounterMap`/`CounterStore`/
  `Counters`, named long tallies per subject. Admitted under the one-module carve-out (see the root
  admission table); never merges with `stats/` in zc-entity.
- [`factor/`](src/main/java/com/ziggfreed/common/factor/CLAUDE.md) - the shared namespaced factor +
  condition MODEL (`FactorContext`/`FactorProvider`/`FactorRegistry`/`FactorCondition`/
  `FactorConditions`) plus `FactorContributions`, the process-wide door one mod claims an id through
  so every other mod's vocabulary can read it. The portable `hytale:` standard library that reads
  real engine data is a split package in zc-entity, not here.
- [`health/`](src/main/java/com/ziggfreed/common/health/CLAUDE.md) - `HealthUtil` (native
  `EntityStats` heal + max-health scale, `Store`/`Ref` and ref-less `Holder` forms).
- [`match/`](src/main/java/com/ziggfreed/common/match/CLAUDE.md) - `NamePattern` (the ONE
  name-pattern grammar: exact / prefix / suffix / contains / catch-all) + `NameMatchRank` (the
  specificity ladder that orders two patterns matching one name). Named by zc-world (a world
  selector) and by consumer mods' trigger keys (the MMO's BonusDrops `When.Match`, mob-scaling's
  validator); it sits at the bottom so a consumer needs no zc-world edge to speak the grammar.
- [`i18n/`](src/main/java/com/ziggfreed/common/i18n/CLAUDE.md) - `Msg`, the mod-agnostic
  client-resolved `Message` factory (caller-prefixed `tr`, `raw`, `join`, `cat`, `bold`/`color`).
- [`inventory/`](src/main/java/com/ziggfreed/common/inventory/CLAUDE.md) - `PlayerAccess` (the
  shared non-deprecated replacements for the engine's deprecated `Inventory` section accessors and
  `Player#getPlayerRef`; here rather than in zc-entity so a consumer needing only the accessor
  shape pulls in no puppet/performer stack) + `InventoryUtil` (give/count/take/spend across
  combined inventory sections) + `InventoryGrant` (hotbar-first single-stack grant ordering, whose
  own section unwraps fold onto `PlayerAccess`). The inventory-snapshot half (`InventorySnapshot`
  and friends) has one consumer only and is on notice per the root admission table.
- [`registry/`](src/main/java/com/ziggfreed/common/registry/CLAUDE.md) - `RegistryLedger`, the
  shared open-registry bookkeeping engine (normalized ids, owner attribution, warn-once overwrite).
- `stats/` - `StackStats` only (a pure item-metadata value record: codecs, an `ItemStack`, nothing
  else). This is one half of a deliberate split package; the ECS bridging engine
  (`EquipStatBridge`, `StatMirror`, `StatChannelAudit`) lives in
  [`zc-entity`'s `stats/`](../zc-entity/src/main/java/com/ziggfreed/common/stats/CLAUDE.md), whose
  router explains why the two halves stay apart. No router of its own (one file).
- `subject/` - `Subject(UUID id, String name, @Nullable Object handle)`, the ONE identity
  vocabulary every engine in the library speaks; the handle is opaque so no engine learns a
  particular player representation. No router of its own (one file, see the class javadoc).
- `cast/` - `WorldEvictors` only, the JVM-global world-resolve + per-world eviction fan-out every
  domain registers into. The rest of the cast/ability runtime lives in
  [`zc-cast`'s `cast/`](../zc-cast/src/main/java/com/ziggfreed/common/cast/CLAUDE.md); this is the
  other half of that split package, kept here because three modules need only this one class and
  not the other 37 in the runtime. No router of its own (one file).
- [`util/`](src/main/java/com/ziggfreed/common/util/CLAUDE.md) - `AssetIndexCache`,
  `NumberFormatter`, `CommandExecutor`, `HostilityUtil`, `EntityIdentifierUtil`,
  `DamageCauseCache`, `JsonTreeUtil`/`JsonOverrideWriter`.
- [`validation/`](src/main/java/com/ziggfreed/common/validation/CLAUDE.md) - the ONE audit-finding
  vocabulary (`Finding`/`Severity`/`ValidationReport`) every content validator in the library
  reports through.

## Shipped resources

`Server/Languages/<locale>/ziggfreedcommon.fmt.lang`, 9 locales (de-DE, en-US, es-ES, fr-FR,
hu-HU, it-IT, pt-BR, ru-RU, tr-TR): the glue/format keys (`ziggfreedcommon.fmt.cat` and friends)
`Msg.cat` depends on. No `.ui` and no `Server/ZiggfreedCommon/` content types; this module ships no
authorable asset TYPE of its own.

## Conventions

Same as the whole library (see the root router's Conventions section): `@Nonnull`/`@Nullable` on
params, log through `CommonLog.LOGGER` (never `ZiggfreedCommonPlugin.LOGGER` from library code),
`ConcurrentHashMap` for shared maps, every engine-touching call try-guarded, world-thread
discipline, no em-dashes. Because this module sits under everything else, an engine-touching call
here is the one most likely to run inside a bare unit-test JVM with no Hytale bootstrap - keep the
try-guards real rather than decorative.

## Tests

25 files in `src/test/java/com/ziggfreed/common/`, one per primitive above plus the factor-model
core (`FactorFormulaTest`, `FactorVocabularyTest`, `FactorContextTest`, `FactorContributionsTest`,
`DerivedFactorTest`, `DerivedFactorValidatorTest`), the name-matching core (`NamePatternTest`,
`NameMatchRankTest`), the codec leaves (`InheritMapCodecTest`, `JsonParentResolverTest`,
`JsonTreeCodecTest`, `JsonOverrideWriterTest`), and `NativeNamesTest`/`GeneratedLangPackTest` for
the i18n overlay primitive. The two cross-cutting guard tests that touch every module
(`AssetCodecInitTest`, `RootRegistrationOnlyTest`) live in the wiring root's own test set, not
here, because they need every module's classes on the classpath at once.
