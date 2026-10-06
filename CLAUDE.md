# ziggfreed-common

The family's shared library of mod-agnostic Hytale primitives and engines. It depends on the Hytale server jar alone: never import the MMO, another sibling mod or Perfect Utils.

## Build and consume

- `.\build.ps1` builds and installs the jar to `$env:HYTALE_MODS_DIR` (`-ModsDir <path>` overrides it, `-Install:$false` builds only).
- `gradle/deprecation-gate.gradle` (run by `check`, in every module) is hyMMO's, copied byte for byte: it changes only by copying hyMMO's.
- Never bundle this jar into a consumer's jar: a second copy under another classloader breaks class identity. A consumer lists `Ziggfreed:ZiggfreedCommon` in its manifest `Dependencies`, so the server loads this jar first.
- Do not add a manifest `SubPlugins` block: a sub-plugin shares the jar's classloader, so a "disabled" domain still links and leaves the library half set up.

## Modules

- Seventeen `zc-*` Gradle modules share the package root `com.ziggfreed.common` and merge into the one jar a server loads; a module compiles only against what its `build.gradle` declares.
- Every inter-module edge is `implementation`. Use `api` only when a public signature re-exports another module's type, and name that type in a comment on the edge.
- Put a new package in the module whose domain owns it, over a one-way edge. When a lower module needs something from a higher one, it declares a seam the wiring root or a consumer fills; a reverse edge is a cycle.
- A package enters `zc-core` only when two or more modules need it and it carries no domain vocabulary.
- Seven packages are split across modules on purpose, so do not consolidate them: `com.ziggfreed.common` and `asset` (the root plus zc-core), `factor` (zc-core plus zc-entity), `cast` (`WorldEvictors` sits in zc-core so any module can register an evictor without a zc-cast edge), `stats` (`StackStats` sits in zc-core), `entity` (`EntityViewers` sits in zc-core so zc-cast's particles and zc-presentation's sound can reach an entity's viewers without a zc-entity edge) and `world` (`SectionBlockCursor` sits in zc-core so zc-cast's look ray and zc-presentation's block sound read a block off its chunk section, as zc-world does, without a zc-world edge).
- `inventory/DisposableItemMetadata` (the declared metadata keys safe to destroy with their item) lives in zc-core because both zc-loot and zc-entity read it and neither may see the other.
- A module shipping `.ui` files needs zc-presentation at runtime even with no compile edge: its pages import `ZigButtons.ui` and `ZigFrames.ui` by path, which Gradle never sees.

## Wiring and seams

- The wiring root and every `*Bootstrap` class hold registration only (`RootRegistrationOnlyTest`). Name a setup-time registration class `*Bootstrap`, or the test never scans it.
- A seam ships filled: a production default in its own module, or a root fill in the same change (`SeamsFilledTest` checks the progression seams). A seam the root fills at setup reports its own fallback once through `SafeLog`, the way `DialogueMemories.persistentBackendOrWarn` does.
- `SeamsFilledTest` and `NpcOffersLivePathTest` reset the shared progression runtime around every test, so a new root test registers what it needs in its own setup.

## Assets

- The library registers every framework store (`asset/FrameworkAssetRegistrar`). A consumer authors JSON under `Server/ZiggfreedCommon/<Type>/` and reads the resolved config lazily, after `LoadedAssetsEvent`; it never registers a framework store again (the engine throws on a second registration).
- A content-like default (words, colours, sounds, icons, item lists) ships as JSON in the owning module's resources; a structural default (what the mechanism does when nothing is registered) stays code.
- After boot, write an asset store only through `asset/AssetStoreWriter`: a direct mutator called on a ticking world thread (a player command, a page handler, a `world.execute` task) deadlocks it. Only the MMO build's `RepoHygieneTest` catches one.
- A content validator reports an unknown id as a `WARNING`, never an `ERROR`: its owner may register later, or be a mod this server does not run.

## Code

- Log through `util.SafeLog`, never the plugin's `LOGGER`: the raw logger throws an `Error` in a unit-test JVM, and it escapes `catch (Exception)`.
- A test that builds a real engine item (`Item`, `ItemStack`, `ItemQuality`, an `ItemToolSpec` array) is tagged `engine-items` and runs in the `engineItemTest` task under the engine's log manager; untagged, it dies in class init. Every other test runs in `test` with no log manager, like a consumer's test JVM, and `check` runs both. Never tag a logging-guard test: it would pass without proving its guard.
- A `.ui` element id is a letter followed by letters or digits; an underscore makes the client refuse the document at join. `UiDocumentSyntaxTest` checks only zc-presentation's documents, so a page `.ui` in any other module goes unchecked.
- A new labeled button is a `ui/ZigRichButton` (a `Button` holding `Label #Label`, labelled on `.TextSpans`), never a `TextButton`, whose label fills no `{0}` params and prints markup tags. The zc-instance pages and the dialogue option row are older `TextButton`s, labelled through `UiText.setText`.

## Release notes

- `patch-notes/` stays mod-agnostic, the developer files in `patch-notes/dev/` included: describe a primitive in "a consumer" terms and never name a consumer's internals or unreleased features. Kweebec Nightmare, the declared exemplar, may be named.
