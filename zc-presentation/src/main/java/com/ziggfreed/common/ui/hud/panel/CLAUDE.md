# ui/hud/panel/

- There is no value-source seam or registry: the reporting mod hands the reading over with each move (`HudPanels.moved`, `movedInWorld`, `itemMoved`, `totalled`), and the call it makes decides which panel the row lands on.
- A row id is opaque and matched whole, ignoring case; an item row's default id is `item:<ItemId>`.
- Where a panel sits is a `HudSpot` asset (`Server/ZiggfreedCommon/HudSpots`), never a corner in Java. The player's pick swaps the whole group; otherwise the panel's spot applies with the panel's inline leaves folded over it; otherwise the document fallback. Every layer is a nullable leaf.
- The bar is a retinted `Group`, not a native `ProgressBar`, which takes its fill from a texture (`BarTexturePath`): per-colour fill images would be one file per member of an open roster.
- Slot ids are positional (`#ZigBarCol<c>`, `#ZigBarC<c>R<r>`), and the slot counts (grid 6x9, stack 2x13) and the Java geometry mirrors in `HudPanelLayout` must match each document's GEOMETRY header.
- Each document's wrapper is full-viewport with no `LayoutMode` (a `LayoutMode: Top` wrapper puts a Bottom-pinned panel at the top), and a Custom UI document resolves textures only under `Common/UI/Custom/`.
- A held row expires at `Long.MAX_VALUE`, never now plus linger, which overflows and drops the row on its first sweep.
- A gain of 10,000 or more binds the shared compact form (`NumberFormatter.compact`, the one `NUMBER-OK` line); below that it is a typed number, and a row's own counting key always gets the whole figure.
- A caption sits in a 30px slot: a number or a word of three or four letters.
- Prove a layout change through the pure shape methods (`HudRowSpread`, `HudPanelHud.shapeOf`) and confirm the drawing in game.
- No product vocabulary (skill, XP, level) in this package.
