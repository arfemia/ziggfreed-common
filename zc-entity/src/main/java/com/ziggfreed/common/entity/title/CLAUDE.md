# entity/title/ - the per-player title record

- `ZigTitleComponent` is the one persisted record of a player's titles (`UnlockedTitles`, `ActiveTitle`): ids lower-cased, `|` and `:` refused, and a shown title is always an unlocked one (`activeTitle()` guards a save that says otherwise). Write it only through zc-objectives' `title/TitleUnlocks`, which announces each change and keeps the mirror current.
- `ActiveTitles` is the off-thread mirror of who shows what: seeded at connect from the saved record, refreshed by every changed write, forgotten at disconnect. A menu naming another player reads the mirror, never that player's store (they may be on another world's thread). It is transient and single-process.
