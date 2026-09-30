# board/ - a rotating view over the quest pool

- Call `BoardEvents.noticeRotation` wherever a board is about to be shown (`ZigBoardPage` does): nothing schedules a rotation, and the first period seen after boot is recorded silently.
- Check a grade gate at accept only, never in the draw, so a locked contract stays on show.
- Add a method to `BoardQuests` only when the board engine itself drives it; a surface asks the quest engine for anything else.
