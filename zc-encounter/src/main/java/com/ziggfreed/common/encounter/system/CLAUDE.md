# encounter/system/

- An add is any NPC carrying the encounter entity's native `SpawnLineage`: the engine stamps one on every encounter and copies it onto whatever its `TriggerSpawners`, or a spawned NPC's own `Spawn`, raises. `EncounterAddSystem` (a `RefChangeSystem`, inside the store's lock) only notes the add against its run through `EncounterRuns`' lineage index; the encounter's tick scales it once by the row's `Scale.Adds` under `EncounterScaling.ADD_MODIFIER_KEY`, never the subject, which carries the lineage too. A row without `Adds` leaves every add as its role made it.
