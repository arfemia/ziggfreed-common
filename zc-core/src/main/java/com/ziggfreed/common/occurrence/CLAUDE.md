# occurrence/ - the one door to recurring events

- A module that must know whether a recurring event is running reads `Occurrences.source()` at the moment of asking and never imports the calendar: zc-calendar fills the slot from `CalendarBootstrap` at library setup.
- Every answer that depends on the time takes the asker's own clock reading (`nowMs`), so an engine on an injected clock asks with it.
- An event the server lacks or its owner switched off is ABSENT: not enabled, never live, no history. Never read "not live" as "switched off"; ask `isEnabled`.
- The two year questions look past the switches: `firstYear` and `currentYear` answer for any LOADED event, so what a player earned in a past run keeps its years after an owner switches the event off. `currentYear` is the year a yearly copy is minted for; whenever `live` answers a run, it answers that run's year. `firstYear` is null for a file with no FirstYear or one outside 1970 to 9999 (such an event never runs), so a reader keeping a copy per year has none to keep.
- An occurrence belongs to the year it STARTS in, and `endMs` is the first instant after it.
- `datesMove` says an event's days move from year to year (a moving rule or per-year days): a reader that would say "every year" asks it first.
