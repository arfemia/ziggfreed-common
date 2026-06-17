# CLAUDE.md - Ziggfreed Common

A **standalone Hytale utility mod** that holds shared, mod-agnostic primitives lifted (config-free) from the MMO Skill Tree mod and the Kweebec Nightmare minigame, so standalone Ziggfreed minigames (and eventually the MMO) consume ONE battle-tested implementation instead of re-deriving 3D sound / camera / HUD plumbing per mod. **Status: v0.1.0 scaffold + primitives** - the plugin stands up and ships the static utils; it registers no systems and bundles no assets.

It has **zero MMO dependency and NO Perfect Utils coupling** - the only dependency is the Hytale server jar. The plugin entry registers nothing; every primitive is a static utility a consumer calls directly.

## Build

Gradle runs via PowerShell (Java 25). Self-contained `build.ps1` builds + installs:
```powershell
cd 'D:\dev\business\hyMMO\additional-mods\ziggfreed-common'; .\build.ps1
.\build.ps1 -Install:$false     # build only
.\build.ps1 -ModsDir <path>     # explicit install target (else $env:HYTALE_MODS_DIR)
```
Produces `build/libs/ZiggfreedCommon-<version>.jar` and copies the runtime jar (never `-sources`/`-javadoc`) into the Hytale `Mods/` folder when `HYTALE_MODS_DIR` is set. `.\gradlew.bat build` works too. The Hytale server jar is referenced `compileOnly` (path in `gradle.properties`: `hytaleHome`/`patchline`/`game_build`) and NEVER bundled; jsr305 is `implementation` (the `@Nonnull`/`@Nullable` annotations must resolve for consumers that compile against this jar).

## Consuming this mod

A consumer mod adds ziggfreed-common as a `compileOnly files(...)` dependency (the local jar path until a remote + submodule gitlink exist) and lists it in its `manifest.json` `Dependencies` (or `OptionalDependencies`) so the server loads it first. NEVER bundle this jar into a consumer (double-loading engine-touching classes under two classloaders breaks identity). All primitives are static, config-free, and reusable as-is.

## Layout

```
settings.gradle / gradle.properties / build.gradle    single Java module (no api subproject)
build.ps1                                              build + auto-install
src/main/resources/manifest.json                       Group:Ziggfreed, ServerVersion ">=0.5.0-pre.0 <0.6.0",
                                                        IncludesAssetPack:false, NO Dependencies
src/main/java/com/ziggfreed/common/
  ZiggfreedCommonPlugin.java                           JavaPlugin entry (LOGGER; setup/shutdown log presence, register nothing)
  sound/                                               Sound3D (3D SoundEvent playback)
  camera/                                              CameraShakeService + ServerCameraService
  util/                                                AssetIndexCache + NumberFormatter + CommandExecutor + HostilityUtil + EntityIdentifierUtil
  feedback/                                            Notify + EventTitles (styled NotificationUtil / EventTitleUtil wrappers)
  ui/                                                  CustomHudHelper (HUD register / strip / restore)
```

## Architecture (per-package routers carry the working detail)

Each domain package carries a nested `CLAUDE.md` router that loads when you touch that subtree. One line each:

- **[`sound/`](src/main/java/com/ziggfreed/common/sound/CLAUDE.md)** - `Sound3D`: play a `SoundEvent` by id at a position OR an entity ref, with a `SoundCategory` + an optional per-listener `Predicate<Ref>`; index resolution through `AssetIndexCache`. World-thread.
- **[`camera/`](src/main/java/com/ziggfreed/common/camera/CLAUDE.md)** - `CameraShakeService` (one-shot `CameraEffect` shake) + `ServerCameraService` (`SetServerCamera` top-down apply/reset). Packet-only; thread-safe writes.
- **[`util/`](src/main/java/com/ziggfreed/common/util/CLAUDE.md)** - `AssetIndexCache` (the "cache ONLY a positive index" resolver), `NumberFormatter`, `CommandExecutor` (execute-only), `HostilityUtil`, `EntityIdentifierUtil`.
- **[`feedback/`](src/main/java/com/ziggfreed/common/feedback/CLAUDE.md)** - `Notify` (Default/Danger/Warning/Success toasts) + `EventTitles` (centered banner). Both take a pre-built `Message`.
- **[`ui/`](src/main/java/com/ziggfreed/common/ui/CLAUDE.md)** - `CustomHudHelper` (install a consumer-built `CustomUIHud` + strip/restore the native HUD).

## Conventions

`@Nonnull`/`@Nullable` on params; `ZiggfreedCommonPlugin.LOGGER` for logging (guard the raw LOGGER behind a try/catch on any path a unit test could reach - the flogger LOGGER throws in a log-manager-less unit JVM). `ConcurrentHashMap` for shared maps. Every engine-touching call is try-guarded so a missing asset / bad ref degrades to a no-op, never a throw into the caller. World-thread discipline: off-thread work hops via `world.execute` before any Store/Ref/Sound/HUD/camera read; packet writes (`writeNoCache`/`write`) are thread-safe off-thread. **No em-dashes anywhere.** Package root `com.ziggfreed.common`.

**Submodule order (when a remote exists):** commit + push HERE first, verify the SHA is on the remote, THEN bump the gitlink in the parent hyMMO repo. Until a remote exists, consumers build against the local jar.

## Release notes

Per-version public release notes in `patch-notes/<version>.md` (frontmatter `version`/`title`/`type: patch-note`/`status`); `patch-notes/_INDEX.md` newest-first. `CHANGELOG.md` is the dev changelog, `README.md` the GitHub front page. **Describe shipped reality, not aspiration** - at scaffold stage say "scaffold + primitives".
