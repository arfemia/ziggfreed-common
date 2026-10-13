# Ziggfreed's CommonLib

The shared library behind Ziggfreed's Hytale mods. It runs their menus, quests, shops, titles and seasonal events, so install it whenever one of them asks for it.

[MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree), [RPG Stations](https://www.curseforge.com/hytale/mods/rpg-stations), [MMO Mob Scaling](https://www.curseforge.com/hytale/mods/mmo-mob-scaling), [Seasons of Orbis](https://www.curseforge.com/hytale/mods/seasons-of-orbis), [Kweebec Nightmare](https://www.curseforge.com/hytale/mods/kweebec-nightmares) and the MMO Skill Tree content packs all need it. On its own it has little to show. The mods built on it fill it with content.

[![Discord](https://img.shields.io/badge/Discord-Join%20Server-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/5NFdZsUxHZ) [![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/ziggfreed)

---

## What you see in game

- The Objective Book, your menu for quests, achievements, the Almanac, Records and reputation. Craft it by hand from leather and plant fiber, or type `/ziggui`.
- Records ranks every player by numbers like Mobs Defeated, Blocks Broken and Playtime, for this week, this month, each season and all time. Boss Fights keeps every fight's wins and best times.
- Titles. Earn them from quests and achievements, then pick one in Settings, under Title, to show beside your name.
- Reputation with characters and factions, from Hated to Exalted. A bar fills as you earn it, and a notice marks each new rank.
- The shop, bounty board and conversation screens that open when you press F on a trader, a board or a character.
- Your boosts on the buff bar, each with a ring that runs down. A gear set's bonus shows there too while you wear it.
- An Almanac page for each seasonal event, with your keepsakes and tallies.
- A Settings tab to hide or move the quest tracker and pick how many notices you see. `/zighud hide` hides them all at once.

## Install

Drop the CommonLib jar into your server's `Mods/` folder, next to the mod that needs it. If a mod asks for a minimum version, use that one or newer. Hytale loads CommonLib first, so there is no load order to manage.

This version needs a Hytale Update 7 server. On Update 6, use 2.2.0.

## For server owners

- CommonLib's settings live in `mods/ziggfreedcommon/`. Switch a seasonal event off in `calendar.json`, show titles in chat with `"$Chat": true` in `titles.json`, or turn Records off with `"$Enabled": false` in `stats.json`.
- The admin commands, such as `/zigprogress`, `/zigcommerce`, `/zignpc` and `/zigcalendar`, need a permission nobody holds until you grant it.

## For mod authors

CommonLib depends on the Hytale server and nothing else. Add it as a `compileOnly` dependency and list `Ziggfreed:ZiggfreedCommon` in your manifest's dependencies so it loads first. Quests, achievements, conversations, shops, boards, placed characters, calendar events, reputations, gear sets, loot and boss fights are plain JSON in your asset pack, with no Java needed, and the Asset Editor knows every one of them. Start with the [CommonLib docs](https://mmo-skill-tree-docs.ziggfreed.com/docs/ziggfreedcommon/).

---

Patch notes for every version are on [GitHub](https://github.com/arfemia/ziggfreed-common/tree/main/patch-notes).

Made by Ziggfreed / [Wintergreen Solutions](https://wintergreen-solutions.com).
