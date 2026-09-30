# objectives/questlist/ - the NPC quest page

- What a character lists combines four answers in `NpcQuestSections.belongsHere`, through `CharacterQuestListing` (which the quest indicators reuse): offers from `NpcOfferProviders`, `readyToTurnInAt`, `acceptSiteOf`, and `canCompleteAt` (asked only of a quest waiting to be collected, or every carried quest would list everywhere). "Given here" is engine data; a consumer registers nothing for it.
- The default offer provider walks the whole catalogue on every ask. Do not index offers by giver until the engine publishes a catalogue generation: a stale giver index is a character offering quests that are no longer theirs.
- A finished quest collected elsewhere reads as parked: ask `canCompleteAt` once per id the character answers to, and claim at the id that answered.
- Accept, hand-in and collect all thread the site id. On collect, raise the toast first, then the completion hand-off (`deps.completion().handOff`), and repaint only when nothing took the screen.
- The page keeps instance state and reopens as `this`, because a partial update runs against the last full build's DOM: rows are addressed through its `BuiltRows` record (see zc-presentation's `ui/rows`).
- The five action buttons are bound once per build with no quest id in the binding and act on whatever the detail panel shows; never put a quest id in their bindings.
- `NpcQuestPages.open` reads the player's own reference off `player`, never off `ref`: at a press-F, `ref` is the character's entity.
