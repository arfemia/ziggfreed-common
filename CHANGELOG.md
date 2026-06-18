# Changelog - Ziggfreed Common

The dev changelog for the shared, mod-agnostic Hytale primitive library. Newest first. No em-dashes.

## 0.3.1
- New: shared `@ZigTabBtnStyle` segmented-tab / filter button in `Common/UI/Custom/Common/ZigButtons.ui` - a center-aligned sibling of `@ZigOptionBtnStyle`. One style covers active and inactive tabs: the consuming page tints the active tab's per-state `.Background.Color` from Java (the same technique the dialogue page uses to paint an option row). Lets a consumer page (a minigame leaderboard's party-size tabs, a paged list's category chips) reuse the shared button look instead of defining its own.

## 0.3.0 and earlier
- The shared mod-agnostic primitives: 3D sound, camera, asset-index cache, command exec, inventory, notifications, HUD helper, surface probe, plus the generic branching NPC dialogue engine and its `.ui` asset pack (`ZigDialoguePage.ui`, `ZigDialogueOptionRow.ui`, `ZigButtons.ui`, `ZigFrames.ui`).
