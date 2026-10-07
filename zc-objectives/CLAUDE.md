# zc-objectives

- No library module may depend on this one: it sits above zc-progression and zc-presentation so it can join them, which makes any edge out of it safe and any edge into it a break.
- Consumer databases store the `ZigProgressComponent` blob verbatim, so its wire format is a contract: append new leaves, and keep `ZigProgressBlobCompatTest`'s golden fixture decoding.
- The book, the NPC page and the title picker paint through zc-presentation's kit (`ui/kit`: `LedgerPainter` rows, `DetailPainter` pages and lines, the tiles), so change a row's, a line's or a page's look in the kit's documents, not in a template of this module's.
