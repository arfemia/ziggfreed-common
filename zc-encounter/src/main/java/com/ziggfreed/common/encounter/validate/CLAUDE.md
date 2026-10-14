# encounter/validate/

- Whether a script sets a boss bar comes from `EncounterScripts`' one cached scan per id, read ahead at the boot self-test (`EncounterSelfTest`) and on every builder reload, never mid-chunk-load; an unreadable script answers no.
- `Once` never heads a blocking action list: the engine spends a `Once` sensor after the instruction's first tick and strands the rest (`EncounterValidator`'s `ENCOUNTER_ONCE_BLOCKS_LIST`). `Once` over a single-tick, non-blocking list is fine.
- A script named after a role replaces that role at load, and the role cannot be recovered (`ENCOUNTER_SCRIPT_ID_IS_ROLE_ID`).
