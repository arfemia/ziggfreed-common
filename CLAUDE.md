# ziggfreed-common

The family's shared library of mod-agnostic Hytale primitives and engines. It depends on the Hytale server jar alone: never import the MMO, another sibling mod or Perfect Utils.

## Build and consume

- `.\build.ps1` builds through the lane and installs the jar (its header lists the flags and the install target, R205).
- Never bundle this jar into a consumer's jar: a second copy under another classloader breaks class identity. A consumer lists `Ziggfreed:ZiggfreedCommon` in its manifest `Dependencies`, so the server loads this jar first.
- Do not add a manifest `SubPlugins` block: a sub-plugin shares the jar's classloader, so a "disabled" domain still links and leaves the library half set up.

## Modules

- Eighteen `zc-*` Gradle modules share the package root `com.ziggfreed.common` and merge into the one jar a server loads; a module compiles only against what its `build.gradle` declares.
- Every inter-module edge is `implementation`. Use `api` only when a public signature re-exports another module's type, and name that type in a comment on the edge.
- Put a new package in the module whose domain owns it, over a one-way edge. When a lower module needs something from a higher one, it declares a seam the wiring root or a consumer fills; a reverse edge is a cycle.
- A package enters `zc-core` only when two or more modules need it and it carries no domain vocabulary.
- Seven packages are split across modules on purpose, so do not consolidate them: `com.ziggfreed.common` and `asset` (the root plus zc-core), `factor` (zc-core plus zc-entity), and `cast`, `stats`, `entity` and `world` (a zc-core class any module reaches without an edge to the owning module).
- A module shipping `.ui` files needs zc-presentation at runtime even with no compile edge: its pages import `ZigButtons.ui` and `ZigFrames.ui` by path, which Gradle never sees.

## Wiring and seams

- The wiring root and every `*Bootstrap` class hold registration only (`RootRegistrationOnlyTest`). Name a setup-time registration class `*Bootstrap`, or the test never scans it.
- A seam ships filled: a production default in its own module, or a root fill in the same change (`SeamsFilledTest` checks the progression seams). A seam the root fills at setup reports its own fallback once through `SafeLog`, the way `DialogueMemories.persistentBackendOrWarn` does.
- `SeamsFilledTest` and `NpcOffersLivePathTest` reset the shared progression runtime around every test, so a new root test registers what it needs in its own setup.

## Assets

- The library registers every framework store (`asset/FrameworkAssetRegistrar`). A consumer authors JSON under `Server/ZiggfreedCommon/<Type>/` and reads the resolved config lazily, after `LoadedAssetsEvent`; it never registers a framework store again (the engine throws on a second registration).
- A content-like default (words, colours, sounds, icons, item lists) ships as JSON in the owning module's resources; a structural default (what the mechanism does when nothing is registered) stays code.
- A content validator reports an unknown id as a `WARNING`, never an `ERROR`: its owner may register later, or be a mod this server does not run.
- A store whose files may carry a top-level `Requires` follows the mod gate (M295): the fold, the owner reader and the drop line are in `zc-core/src/main/java/com/ziggfreed/common/asset/CLAUDE.md`, the `ModGates` API in `factor/CLAUDE.md`, a reward row's own gate in zc-progression's `progress/asset/CLAUDE.md`; read the first before adding or changing a gated store.

## Code

- Log through `util.SafeLog`, never the plugin's `LOGGER`: the raw logger throws an `Error` in a unit-test JVM, and it escapes `catch (Exception)`.
- A test that builds a real engine item (`Item`, `ItemStack`, `ItemQuality`, an `ItemToolSpec` array), or whose subject runs an engine class that logs at class init (Update 7's `FileUtil` behind a store's atomic write), is tagged `engine-items`, that method alone, and runs in the `engineItemTest` task under the engine's log manager; untagged, it dies in class init. Every other test, a logging guard included, runs in `test` with no log manager, like a consumer's test JVM, and `check` runs both: a tagged guard would pass without proving its guard.
- A new labeled button is a `ui/ZigRichButton` (a `Button` holding `Label #Label`, labelled on `.TextSpans`), never a `TextButton`, whose label fills no `{0}` params and prints markup tags.
- Before adding a `/zig*` admin verb in any module, read `zc-core/src/main/java/com/ziggfreed/common/command/CLAUDE.md` (permission nodes, description keys, one command per verb, the target-player base); it does not load from another module.

## Release notes

- `patch-notes/` stays mod-agnostic, the developer files in `patch-notes/dev/` included: describe a primitive in "a consumer" terms and never name a consumer's internals or unreleased features. Kweebec Nightmare, the declared exemplar, may be named.
