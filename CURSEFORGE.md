# Ziggfreed's CommonLib

**The shared library behind Ziggfreed's Hytale mods. A required dependency for some of them, and a toolkit for modders who want to build their own.**

CommonLib is the battle-tested plumbing that Ziggfreed's mods stand on: 3D sound, camera effects, notifications and HUD helpers, a branching NPC dialogue engine, and a full co-op instance framework (parties, queues, leaderboards, rewards). Shipping it once means every mod that uses it behaves the same way and gets fixed in one place.

[![Discord](https://img.shields.io/badge/Discord-Join%20Server-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/5NFdZsUxHZ) [![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/ziggfreed)

---

## Do I need this?

If a mod's page lists **Ziggfreed's CommonLib** as a required dependency, yes: install it alongside that mod and you are done. If nothing you run asks for it, you do not need it.

There is nothing to set up. CommonLib adds **no commands, no config files, no items, and no gameplay of its own**. It loads quietly, the mods that depend on it do the rest, and it stays out of the way otherwise.

## Install

1. Download `ZiggfreedCommon-<version>.jar` from the Versions tab.
2. Drop it into your server's `Mods/` folder, next to the mod that needs it.
3. Make sure your Hytale server is in the **Update 5** range (0.5.x).

Load CommonLib **before** the mods that depend on it (Hytale handles this automatically when a mod declares the dependency).

---

## For modders

CommonLib is a mod-agnostic primitive library. Its only dependency is the Hytale server jar (zero coupling to any other mod), every utility is a static call, and it is reusable as-is. Add it as a `compileOnly` dependency, list it in your `manifest.json` dependencies so the server loads it first, and call what you need.

It is the foundation under **Kweebec Nightmare**, and is built to back any future Ziggfreed minigame, dungeon, or raid.

### What's inside

- **3D sound** - play any `SoundEvent` at a position or on an entity, with category and per-listener filtering.
- **Camera** - one-shot screen-shake effects and a top-down server camera apply/reset.
- **Notifications and titles** - styled toast notifications (Default / Danger / Warning / Success) and centered event banners.
- **HUD and in-page toasts** - install a custom HUD with strip/restore of the native one, plus a transport-agnostic in-page toast engine (toasts play a per-severity sound) and a clickable rich-text button primitive for styled / parameterized labels.
- **UI theming** - a generic palette-to-selector retint engine (recolor a shared menu frame / panels / buttons in place, or swap a bespoke 9-slice texture set, from one call) plus a mod-agnostic theme value model (a colour-slot palette + a theme record) a consumer folds over the asset backbone and gates with its own policy.
- **Branching NPC dialogue engine** - a generic conversation system (nodes, options, conditions, actions) with its own UI, native `Parent` inheritance for reuse, pack-authorable per-kind option colour/glyph theming, reusable shared option fragments, and a structural validator. Ships its UI asset pack.
- **Co-op instance framework** - a searchable-invite party system, a Public / Party / Solo play-and-queue screen with a live launch timer, an end-game results screen, a bucketed leaderboard, and an asset-driven reward model with a no-loss inventory-full guard.
- **Encounter framework** - timed and banded per-entity effects (on-hit slows, debuffs, escalation bands), weighted spawn rosters with a wave director, a co-op zone-hold objective timer (extraction pads, capture points, king-of-the-hill), and floor-snapped runtime spawn placement.
- **Difficulty scaling** - a domain-free engine that folds a group of participant power scalars (by average / peak / weighted / solo) into a band-clamped effective difficulty over an authored floor, serving both open-world proximity groups and instanced parties from one abstraction, plus a ref-less pre-add health-scaler and NPC role-identity reads for scaling a mob at spawn.
- **World helpers** - a surface probe that floor-snaps placements onto procedural terrain, world-map and compass POI markers (global and per-player), a hidden-until-discovered POI tracker, world time-of-day + forced-weather control, and per-player forced music.
- **Utilities** - asset-index cache, number formatter, command execution, hostility checks, entity identifiers, and a memoized damage-cause index, plus inventory helpers for custom resource items.

### Author your own content, no Java required

Where it makes sense, CommonLib is driven by asset-pack JSON under `Server/ZiggfreedCommon/` (dialogues and dialogue templates, dialogue option themes, instance presets, multi-phase bosses, banded effects, encounter rules, prefab placements, leaderboard layouts, and party settings), resolved `defaults < pack < owner`. A content pack can tune the framework without touching code.

---

## Versions

| Version           | Notes                                                                                                                                                                                                                                                       |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1.2.0             | Adds a generic, domain-free difficulty-scaling engine (a scaling context over participant power scalars, folded by a selectable aggregation mode and resolved to a band-clamped effective difficulty) that serves both open-world groups and instanced parties, plus a ref-less pre-add health-scaler and NPC role-identity reads for spawn-time scaling. Also a dialogue-engine authoring pass: native `Parent` inheritance (replacing the old template DSL), data-driven pack-authorable per-kind option colour/glyph theming (`DialogueOptionTheme` assets), an option `Style` field, and reusable shared option fragments. All additive over 1.1.1. |
| 1.1.1             | Adds a page-less way to float an in-menu toast: `ToastablePage.showOnActive` shows a toast over whichever toastable page a player has open, so a service / event system / dialogue action can route sound + toast together without holding a page reference. Additive over 1.1.0. |
| 1.1.0             | Adds a generic UI retint engine + a mod-agnostic theme value model (recolor a shared menu frame/buttons, or swap a 9-slice texture set, from a palette), a clickable rich-text button primitive, per-severity toast sounds + a dialogue quest-completion toast, world time-of-day + forced-weather control, and per-player forced music. All additive over 1.0.0. |
| 1.0.0             | First stable release. The primitives, the dialogue engine, and the co-op instance + encounter framework are feature-complete for the 1.0 consumer surface. Adds runtime boss health scaling, boss world-map marker knobs, and same-identity reward merging. |
| 0.3.2             | Adds a reusable co-op "hold this zone" objective timer (extraction pads, capture points, king-of-the-hill) and per-phase helper-throwable cluster knobs on the multi-phase boss primitive.                                                                  |
| 0.3.1             | Adds a shared segmented-tab / filter button style for consumer pages (party-size tabs, category chips).                                                                                                                                                     |
| 0.3.0 and earlier | The core primitives (3D sound, camera, asset-index cache, command exec, inventory, notifications, HUD helper, surface probe) plus the branching NPC dialogue engine and its UI asset pack.                                                                  |

Made by Ziggfreed / [Wintergreen Solutions](https://wintergreen-solutions.com).
