# Ziggfreed's CommonLib

**The shared library behind Ziggfreed's Hytale mods. A required dependency for some of them, and a toolkit for modders who want to build their own.**

CommonLib is the plumbing Ziggfreed's mods stand on: quest and achievement engines, an economy of wallets, shops and bounty boards, a branching NPC dialogue engine, placed NPCs, 3D sound, camera effects, toasts and HUD helpers, and a co-op instance framework with parties, queues and leaderboards. Shipping it once means every mod built on it behaves the same way and gets fixed in one place. [MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree), [RPG Stations](https://www.curseforge.com/hytale/mods/rpg-stations), [MMO Mob Scaling](https://www.curseforge.com/hytale/mods/mmo-mob-scaling) and Kweebec Nightmare all run on it.

[![Discord](https://img.shields.io/badge/Discord-Join%20Server-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/5NFdZsUxHZ) [![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/ziggfreed)

---

## Do I need this?

If a mod's page lists **Ziggfreed's CommonLib** as a required dependency, yes: install it alongside that mod and you are done. If nothing you run asks for it, you do not need it.

There is nothing to set up. CommonLib ships no gameplay of its own; it loads first and the mods that depend on it do the rest. Its two admin commands, `/zigprogress` (quests and achievements) and `/zigcommerce` (the economy), are permission-gated, so nobody holds them until you grant the node, and the mods built on it usually ship their own friendlier aliases anyway. The few files it writes live under `mods/ziggfreedcommon/` and only matter when you want to override something a mod shipped: switch off a placed NPC, reword a quest notice, retune a shop.

## Install

1. Download `ZiggfreedCommon-<version>.jar` from the Versions tab. If a mod asks for a minimum version, take that one or newer.
2. Drop it into your server's `Mods/` folder, next to the mod that needs it.
3. Make sure your Hytale server is on **Update 6** (0.6.x).

Hytale loads CommonLib before the mods that declare it as a dependency, so there is no load order to manage by hand.

---

## For modders

CommonLib is a mod-agnostic library. Its only dependency is the Hytale server jar - zero coupling to any other mod. Add it as a `compileOnly` dependency, list it in your `manifest.json` dependencies so the server loads it first, and call what you need. The primitives are static calls; the bigger engines (dialogue, quests, achievements, commerce) are registries your mod contributes into at setup, and content for them is plain JSON in your asset pack.

It is the foundation under MMO Skill Tree, RPG Stations, MMO Mob Scaling, and Kweebec Nightmare, and is built to back any future minigame, dungeon, or raid.

### What's inside

- Quest and achievement engines. Content is authored as JSON assets with inheritance, sharing one reward vocabulary and one requirements block; repeatable quests, points, ladders and server-firsts come with it, plus a ready-made quest log / achievement browser screen and an on-screen tracked-quest HUD.
- An economy: wallets (a server-kept number, or backed by a real item the player carries), shops with rotating shelves, bounty boards, one price model, per-player purchase limits, and the two shop and board screens.
- A branching NPC dialogue engine with its own screen. One engine per server: every mod contributes its vocabulary additively, so two dialogue mods coexist instead of fighting over conversations.
- Asset-driven NPC placement. Where a character stands, in which worlds, and what pressing F on it opens is one JSON file, with an owner on/off switch and live admin tooling.
- A placed-block ledger, so a mod counting block breaks never pays a player for breaking what they placed themselves.
- Feedback moments: what a server does when a quest completes or an achievement unlocks (a toast, a banner, a jingle, a console command) is a small file any pack can replace by name.
- Presentation primitives: 3D sound, camera shakes, styled toasts and titles, custom HUD install and restore, and a UI theming engine that retints shared menus from a palette.
- A co-op instance framework: parties with invites, a Public / Party / Solo queue screen with a live launch timer, an end-of-round results screen, bucketed leaderboards, and a reward model with an inventory-full guard.
- Encounter tools for minigames and dungeons: timed and escalating per-entity effects, weighted spawn waves, hold-this-zone objectives, floor-snapped spawn placement, and a multi-phase boss primitive.
- A difficulty-scaling engine that folds the power of the players present into one effective difficulty, for open-world groups and instanced parties alike.
- A cast and ability runtime: step dispatch, on-hit resolution, ray targeting, armed states, per-world tick queues.
- Item plumbing: a loot core shared by chests, mob drops and rewards; stat rolling onto items a player already owns, with budgets and caps; and an equip bridge that turns item-carried stats into real entity stats.
- World helpers: one shared "which worlds does this apply to" selector, block read/write, surface probes, world-map and compass markers, hidden-until-discovered points of interest, time-of-day and weather control, per-player music.
- Utilities: asset-index caching, number formatting, command execution with placeholder substitution, entity identity reads, inventory grant and spend helpers.

### Author your own content, no Java required

Where it makes sense, CommonLib is driven by asset-pack JSON under `Server/ZiggfreedCommon/`: quests, achievements, dialogues, currencies, shops, boards, loot tables, NPC placements, feedback moments, instance presets, bosses, leaderboards and more, resolved `defaults < pack < owner`. A content pack can add or retune all of it without touching code, and the in-game Asset Editor understands every one of these types.

---

## Versions

| Version           | Notes                                                                                                                                                                                                                                                       |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2.0.0             | The library's biggest release, and the one MMO Skill Tree 1.6.0 runs on. Adds the shared quest and achievement engines with content as JSON assets, the whole economy (wallets, shops, rotating shelves, bounty boards) and its screens, the quest log / achievement browser screen and tracked-quest HUD, asset-driven NPC placement, the placed-block ledger, editable notice files for quest and achievement moments, the `/zigprogress` and `/zigcommerce` admin commands, one shared dialogue engine per server with declared memories and once-only lines, the difficulty-scaling engine, the cast/ability runtime, and in-game Asset Editor support for every authored type. Requires a Hytale Update 6 (0.6.x) server. |
| 1.1.1             | The last Update 5 release. Adds a page-less way to float an in-menu toast over whichever screen a player has open, so a service or dialogue action can route sound and toast together without holding a page reference. Additive over 1.1.0. |
| 1.1.0             | Adds the UI retint engine and theme model (recolor a shared menu frame or swap a texture set from one palette), a clickable rich-text button primitive, per-severity toast sounds, world time-of-day and forced-weather control, and per-player forced music. Additive over 1.0.0. |
| 1.0.0             | First stable release. The primitives, the dialogue engine, and the co-op instance and encounter framework are feature-complete. Adds runtime boss health scaling, boss world-map marker knobs, and same-identity reward merging. |
| 0.3.2             | Adds a reusable co-op "hold this zone" objective timer (extraction pads, capture points, king-of-the-hill) and per-phase helper-throwable cluster knobs on the multi-phase boss primitive.                                                                  |
| 0.3.1             | Adds a shared segmented-tab / filter button style for consumer pages (party-size tabs, category chips).                                                                                                                                                     |
| 0.3.0 and earlier | The core primitives (3D sound, camera, asset-index cache, command exec, inventory, notifications, HUD helper, surface probe) plus the branching NPC dialogue engine and its UI asset pack.                                                                  |

Made by Ziggfreed / [Wintergreen Solutions](https://wintergreen-solutions.com).
