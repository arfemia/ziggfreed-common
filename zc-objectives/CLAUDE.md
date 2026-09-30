# zc-objectives

- No library module may depend on this one: it sits above zc-progression and zc-presentation so it can join them, which makes any edge out of it safe and any edge into it a break.
- Consumer databases store the `ZigProgressComponent` blob verbatim, so its wire format is a contract: append new leaves, and keep `ZigProgressBlobCompatTest`'s golden fixture decoding.
- The book and the NPC page append zc-presentation's shared `Pages/ZigDetailLine.ui` for every quest step, heading and reward line (and `Pages/ZigSelectRow.ui` for the NPC page's list rows), so change a line's look there, not in a template of this module's.
