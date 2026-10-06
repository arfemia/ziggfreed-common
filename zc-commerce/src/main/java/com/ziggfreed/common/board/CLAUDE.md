# board/ - a rotating view over the quest pool

- Call `BoardEvents.noticeRotation` wherever a board is about to be shown (`ZigBoardPage` does): nothing schedules a rotation, and the first period seen after boot is recorded silently.
- Check a grade gate at accept only, never in the draw, so a locked contract stays on show.
- Add a method to `BoardQuests` only when the board engine itself drives it; a surface asks the quest engine for anything else.
- A list of what a player took off a board reads `namingBoard`, never `membersOf`: a contract the board stopped posting while it was carried (switched off, or hidden by a feature) must stay reachable for its hand-in and its claim.
- A slot's own `Requires` is an accept gate too, checked after the band's: it locks the contract that slot posted for the player who fails it and never hides the slot (the draw is the server's). The posting's slot is `BoardEngine.postedSlot` (a reroll keeps its position's slot), so a board surface calls the slot-aware `canAccept` / `accept` / `acceptGateRefusals`; the slot-less forms know no slot gate.
