# calendar/spawn/

- Never remove a world-spawn rule: the engine's removal looks a rule up in a table that answers 0 for a rule it never set up and removes spawn configuration 0 instead. A rule no running event owns is retired (`MoonPhaseWeightModifiers [0]`, `CalendarSpawnPlan.retired`). One rule per (role, environment) pair: the engine refuses a second with a SEVERE line (`CalendarSpawnPlan.sharedPairs`).
