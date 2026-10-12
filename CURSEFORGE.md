# Ziggfreed's CommonLib

**The shared library behind Ziggfreed's Hytale mods. A required dependency for some of them, and a toolkit for modders who want to build their own.**

CommonLib is the plumbing Ziggfreed's mods stand on: quest and achievement engines, an economy of wallets, shops and bounty boards, a branching NPC dialogue engine, placed NPCs, 3D sound, camera effects, toasts and HUD helpers, a co-op instance framework with parties, queues and leaderboards, a boss framework over the game's own encounter scripts, a yearly calendar for seasonal events, and reputation on the game's own system. Shipping it once means every mod built on it behaves the same way and gets fixed in one place. [MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree), [RPG Stations](https://www.curseforge.com/hytale/mods/rpg-stations), [MMO Mob Scaling](https://www.curseforge.com/hytale/mods/mmo-mob-scaling) and Kweebec Nightmare all run on it.

[![Discord](https://img.shields.io/badge/Discord-Join%20Server-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/5NFdZsUxHZ) [![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/ziggfreed)

---

## Do I need this?

If a mod's page lists **Ziggfreed's CommonLib** as a required dependency, yes: install it alongside that mod and you are done. If nothing you run asks for it, you do not need it.

There is nothing to set up. CommonLib ships no gameplay of its own; it loads first and the mods that depend on it do the rest. Its admin commands, `/zigprogress` (quests and achievements), `/zigcommerce` (the economy), `/zignpc` (placed NPCs), `/zigflair` (unlocked flairs), `/zigencounter` (boss fights), `/zigcalendar` (seasonal events), `/zigloot validate` (a check of every loot table), `/zigtitle` (granting titles, even to a player who is offline) and `/zigstats` (looking up a player's stats), are permission-gated, so nobody holds them until you grant the node, and the mods built on it usually ship their own friendlier aliases anyway. Every player holds a handful: `/ziggui` (the menu), `/zighud` (their own settings), `/zigalmanac open`, `/zigtitle open`, `/zigcostume off` and `/zigleaderboard encounter`. They have little to show until a mod or pack ships the content behind them.

The few files it writes live under `mods/ziggfreedcommon/` and only matter when you want to override something a mod shipped: switch off a placed NPC, reword a quest notice, retune a shop, or turn a seasonal event off in `calendar.json`.

## Install

1. Download `ZiggfreedCommon-<version>.jar` from the Versions tab. If a mod asks for a minimum version, take that one or newer.
2. Drop it into your server's `Mods/` folder, next to the mod that needs it.
3. Make sure your Hytale server is on **Update 7** (0.7.x). A server still on Update 6 stays on 2.2.0.

Hytale loads CommonLib before the mods that declare it as a dependency, so there is no load order to manage by hand.

---

## For modders

CommonLib is a mod-agnostic library. Its only dependency is the Hytale server jar - zero coupling to any other mod. Add it as a `compileOnly` dependency, list it in your `manifest.json` dependencies so the server loads it first, and call what you need. The primitives are static calls; the bigger engines (dialogue, quests, achievements, commerce) are registries your mod contributes into at setup, and content for them is plain JSON in your asset pack.

It is the foundation under MMO Skill Tree, RPG Stations, MMO Mob Scaling, and Kweebec Nightmare, and is built to back any future minigame, dungeon, or raid.

### What's inside

- Quest and achievement engines. Content is authored as JSON assets with inheritance, sharing one reward vocabulary and one requirements block; repeatable quests, points, ladders and server-firsts come with it, plus a ready-made quest log / achievement browser screen and an on-screen tracked-quest HUD. An achievement can be yearly, one copy for each year a calendar event runs, and a capstone can pick its own set by category or tags. Steps can count using an item (only throws, if you like) or receiving a reward, and one step can count a station and its greater tier together by their shared name. A finished quest counts toward other quests and achievements on any server, once, when its reward is collected.
- An economy: wallets (a server-kept number, or backed by a real item the player carries), shops with rotating shelves, bounty boards, one price model, per-player purchase limits, and the two shop and board screens. A shop, board, offer or wallet can follow a mod's feature switch, gone while it is off and back when it is on, with no coin or open contract lost. A shop or board can also belong to chosen worlds (a dungeon's own trader, say). One slot on a board can ask for something of its own, such as a rank: everyone sees the job it posts, and only players who meet it can take it.
- A branching NPC dialogue engine with its own screen. One engine per server: every mod contributes its vocabulary additively, so two dialogue mods coexist instead of fighting over conversations. A line can come back each day or week and pay out anything a quest reward can. One file adds a line to conversations another pack wrote. Conversations also run quests on their own, with no other mod: a line can offer one, take it and hand it in.
- Asset-driven NPC placement. Where a character stands, in which worlds, and what pressing F on it opens is one JSON file, with an owner on/off switch and live admin tooling. A pack can stand up a quest giver from the library's template without writing how it behaves, and a character can arrive and leave with a calendar event.
- A yearly calendar. A pack dates an event that comes round every year and the library runs it on those dates: content shows only while it is on, each player gets a banner, the event's own creatures spawn, and quests can count taking part. The Almanac gives each event a page: whether it is on, the seasons a player took part in, their keepsakes and their lifetime tallies.
- Titles. You earn one from a quest or an achievement and pick the one you show in Settings, under Title, which counts how many you have earned ("2 of 5"). A title you earn while showing none shows by itself, unless you picked Show none. A title can stay secret until it is yours, and an admin can grant or take one away while you are offline: it lands at your next login. It appears beside your name on Records and on the results, party and queue screens. Chat shows it only on a server whose owner switches that on (`"$Chat": true` in `mods/ziggfreedcommon/titles.json`, where an owner can also make any title secret or stop new ones showing by themselves). It never floats over your head.
- Reputation, on the game's own system. A pack makes a faction, or a single character, something players earn standing with: rewards and kills pay it, on one ladder from Hated to Exalted that each pack can rename or run on past the top. Gear can add to it while worn, shown apart. Standing moves a bar on screen, and a notice marks each new rank. The game keeps the number itself, so it survives death.
- A shared menu for quests, achievements, the Almanac, Records and reputation, opened with the Objective Book (crafted by hand from leather and plant fiber) or `/ziggui`. Other mods can add their own tabs to it, and it takes their theme.
- A Settings tab at the end of that menu. Each player hides or moves the quest tracker and the progress displays, picks how many quest and achievement notices they see, and changes their title. Server owners set the defaults and can fix any of them for everyone.
- Records, the menu's tab that ranks every player's numbers. Combat, Gathering and Adventure each rank their own (Mobs Defeated, Blocks Broken, Quests Completed and Playtime among them), over All Time, This Week, This Month and a tab for each season, named with its year and still there after the season ends. Renown ranks the game's own objectives you have finished and your standing with each reputation, over all time. Boss Fights gives every fight on record a tab of its own with Wins, Best Time, Best Share and Damage. A mod adds its own stats and sections as JSON, and a column nothing on the server counts never shows. Owners switch Records off, or move the day a week starts, in `mods/ziggfreedcommon/stats.json`.
- A placed-block ledger, so a mod counting block breaks never pays a player for breaking what they placed themselves.
- Feedback moments: what a server does when a quest completes or an achievement unlocks (a toast, a banner, a jingle, a console command) is a small file any pack can replace by name.
- Presentation primitives: 3D sound, particles and sounds that ride an entity, camera shakes, styled toasts and on-screen titles, custom HUD install and restore, and a UI theming engine that retints shared menus from a palette.
- Timed boosts on the game's own buff bar. A mod reports a boost or a blessing and when it ends, and you see it as an icon beside your other effects, its ring running down with the time left. It stays up when you log back in or change worlds. A boost with no icon of its own takes one of the library's two (one for your own boosts, one for those running for the whole server), and up to four of each show side by side.
- A co-op instance framework: parties with invites, a Public / Party / Solo queue screen with a live launch timer, an end-of-round results screen, bucketed leaderboards, and a reward model with an inventory-full guard.
- A boss framework over the game's own encounter scripts: the fight is authored natively, and the library adds credit for the whole party, loot scaled by each player's share, notices, a tab on Records' Boss Fights, a map marker and quest progress, from one binding row per fight and with no Java in a pack. A fight's adds can grow their health with the party, and `/zigleaderboard encounter` opens its records on any server. Beside it, encounter tools for minigames and dungeons: timed and escalating per-entity effects, hold-this-zone objectives and floor-snapped spawn placement.
- Damage types beside Hytale's own: Arcane, Void and Life, with their own colours and death lines. Gear can raise one type's damage (Fire, Void and the rest) by a percent or a flat amount, and the tooltip names the bonus. A mod tags its own hits to tell them apart while they keep the game's types.
- A difficulty-scaling engine that folds the power of the players present into one effective difficulty, for open-world groups and instanced parties alike.
- A cast and ability runtime: step dispatch, on-hit resolution, ray targeting, armed states, per-world tick queues.
- Item plumbing: a loot core shared by chests, mob drops and rewards; stat rolling onto items a player already owns, with budgets and caps, and an equip bridge that turns item-carried stats into real entity stats. Also a lookup over the game's own recipes (what a bench makes, what uses up an item, what makes one, an item's own crafting recipe), conditions that read one item's quality, level, wear and bonuses, and a server-wide list of which data on an item a mod has said may go with it, so a mod that breaks items down can refuse one carrying someone else's data.
- Gear sets. One JSON file names a set's pieces and its tiers of bonuses, with an optional look, and wearing the pieces pays them. While a bonus is on, the set shows one icon on the buff bar, with its name when you hover it, and a set that gives each bonus its own icon changes it as you put on more of the set. A set file can also join another pack's set, adding its pieces and bonuses to it, so you see one set with one notice carrying every line.
- An item's use can count toward a quest step or pay a reward. It can also put on a costume, which shows on the buff bar like any other effect, and the wearer can always take one off with `/zigcostume off`. The library's effect check, run in a mod's own build, fails a costume with no icon, or an effect icon with no name to show on hover.
- Bonus finds: one small file per find hands out something extra when a player breaks a block, harvests by hand or kills a mob, and it lands where that happened. `/zigloot validate` checks every loot table and reward kind, and the finds too, for mistakes that quietly pay nothing.
- World helpers: one shared "which worlds does this apply to" selector, block read/write, surface probes, world-map and compass markers, hidden-until-discovered points of interest, time-of-day and weather control, per-player music.
- Utilities: asset-index caching, number formatting, command execution with placeholder substitution, entity identity reads, inventory grant and spend helpers.

### Author your own content, no Java required

Where it makes sense, CommonLib is driven by asset-pack JSON under `Server/ZiggfreedCommon/`: quests, achievements, dialogues, currencies, shops, boards, loot tables, bonus finds, NPC placements, gear sets, calendar events, Almanac pages, titles, reputations, feedback moments, objective kinds, boss-fight bindings, instance presets, leaderboards and more, resolved `defaults < pack < owner`. A content pack can add or retune all of it without touching code, and the in-game Asset Editor understands every one of these types. Where a shop, board, wallet or calendar file names an item, the editor offers its own item picker.

---

Patch notes for every version are on [GitHub](https://github.com/arfemia/ziggfreed-common/tree/main/patch-notes).

Made by Ziggfreed / [Wintergreen Solutions](https://wintergreen-solutions.com).
