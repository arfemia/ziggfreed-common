# CLAUDE.md - zc-encounter

The boss framework over the engine's own `EncounterManager` (server 0.6, Update 6). The FIGHT is a
native encounter script under `Server/EncounterManager/` and the engine runs it: states, phases,
health thresholds, in-place role swaps, invulnerability, adds, the boss bar, the music, membership,
hot reload. This module gives that fight what the engine leaves out, all OUTSIDE the script: a voice
(six native `IEvent`s off the script's `SignalWorldEvent` beats), a ledger (who fought, weighted and
share-normalised), a scale (party and power health scaling, keyed and reconciled) and a payout
(loot, feedback moments, a map marker). Nothing here re-implements a native mechanism: no phase
engine, no HP poll, no bar, no add spawner, no music pusher, no backstop.

## Build

Part of the fourteen-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-encounter`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build. The
manifest declares `Hytale:NPC` and `Hytale:EncounterManager` as dependencies, so `NPCPlugin.get()`
and the encounter component type are live in `setup()` when the Types and systems register.

## Dependencies

- **Depends on**: `zc-core` (SafeLog, `Subject` + `PlayerRefSubjectHandle`, the factor model, the
  name-pattern ladder, `validation/`, the asset bases + `OwnerLayerReader`, `NativeEventSeam`,
  `HealthUtil`, `CommandRunner`), `zc-entity` (`HytaleFactors`), `zc-world` (`WorldSelector` +
  `MatchRank`, `WorldMapMarkers`), `zc-effects` (the native-effect primitive a phase reaction
  composes with), `zc-loot` (`LootRef`, `LootEngine`, `RewardGrants`, the reward kinds,
  `NativeLootService`), `zc-presentation` (`FeedbackEngine`), `zc-scaling` (the power fold).
- **Depended on by**: `zc-objectives` (`ZigEncounterProducer`, the three encounter objective kinds'
  producer, and `EncounterQuestAxes`, the encounters generator axis over the folded binding rows)
  and `zc-instance` (`EncounterLeaderboardListener`, the one class that writes a defeat's rows).
  Both point AT this module and only listen to its events or read its folded rows.
- **Reverse-edge trap, BUILD-ENFORCED**: this module never imports `zc-instance` (except the two
  packages that only look like it: `instance.reward` is zc-loot, `instance.effect` is zc-effects),
  `zc-objectives`, `zc-progression`, `zc-dialogue` or `zc-commerce`. `EncounterEdgeTest` scans every
  import and fails the build on one. What it needs from those modules it asks through the four seams
  in `seam/EncounterSeams`, which the wiring root fills.

## Packages

| Package | What it is |
|---|---|
| `encounter/` | `EncounterBootstrap`: the one registration phase the root calls (run component, Types, systems, reload listener, audit, `/zigencounter`, boot self-test). Registration only; `RootRegistrationOnlyTest` holds it to that |
| `asset/` | the two Pattern A types. `EncounterBindingAsset` (`Server/ZiggfreedCommon/Encounters/<Id>.json`): what the server OWES for a script it does not describe, nine nested groups (`Subject`, `Participation`, `Scale`, `Timing`, `Loot`, `Leaderboard`, `Progression`, `Feedback`, `Discovery`) plus `EncounterAsset`, `NameKey`, `Enabled`; every leaf nullable, `BindingRestatesNothingTest` proves no key names a phase, threshold, count, sound, role or spawner. `EncounterParticipationAsset` (`Server/ZiggfreedCommon/EncounterParticipation/<Id>.json`): how credit is shared, matched per subject `Match` (a `NamePattern`) and per world `Where`, three `FactorFormula` weights, `MinShare`, `CreditDead`, `CreditDisconnected`, `Enabled`; `ParticipationRules` picks the most specific on the shared ladders; `ParticipationSpec` lays the row's override over the matched rule over the structural posture. `EncounterOwnerLayers` reads `mods/ziggfreedcommon/{encounters,encounter-participation}.json` through zc-core's `OwnerLayerReader` |
| `signal/` | `EncounterSignal`, the `zc:<moment>[:<detail>]` grammar (engaged, phase:<State>, wave[:<label>], defeated, reset; anything else is the author's own beat), and `EncounterSignalSystem`, the `EntityEventSystem<EntityStore, WorldEventSignal>` queried on the encounter component that hears every script's beats. Registered through `registerSystem`, never `registerEntityEventType` |
| `run/` | `ZigEncounterRun`, the codec-less run component (registered with the bare-supplier overload, so no chunk save carries it; subject keyed by uuid, re-resolved every read); `EncounterRun` the snapshot; `ZigEncounterRest` + `EncounterRest`, the ONE persisted (codec-backed, `ZiggfreedCommon:EncounterRest`) component on the encounter entity: the world-clock instant the site's rest ends, stamped at a defeat from the row's `Timing.Rest` through the calling system's `CommandBuffer` (the store refuses a structural change from inside a system), saved with the chunk, never cleared (a passed instant reads as rested, the next defeat writes over it), read by the `ZigRested` sensor and `/zigencounter inspect`; `EncounterRuns` the live table, the one `ParticipationLedger` and the two reference-keyed hot indexes the damage system reads; `EncounterLifecycle`, the story in order (engage, phase, signal, defeat, wipe, reset) with one `[encounter]` line per beat; `EncounterSubjects` (the `TargetSlot` read off `MarkedEntitySupport`), `EncounterMembership` (seed re-stamp, eject), `EncounterScaling` (the clamp formula over `HealthUtil`, key `zc_encounter_scale`), `EncounterSpawner` (the engine's own holder recipe, `spawnWhenLoaded` bringing the chunk up ticking first for a console or remote placement, `despawn`), `EncounterChunkHold` (an open fight resets its chunk's active timer each tick, the engine's own lever for a chunk a player stands in, so a cold chunk cannot cut the wipe grace or the run budget short; a settled run lets go), `EncounterRuntime` (`runOf`, `isBoundSubject`, `state`, `setState`), `EncounterFactors` (the module's factor registry: `hytale:` + the five `ziggfreedcommon:encounter_*` readings) |
| `ledger/` | `ParticipationLedger`, pure: three counters per `(runId, playerUuid)`, `shares()` normalised against the top contributor, `MinShare` and the dead-member rule; `ParticipationShares`, `ParticipantShare`, `ParticipationWeights` |
| `system/` | `EncounterLifecycleSystems.Attach` (a run on every encounter entity before its first tick) + `.Remove` (the run ends with the entity, REMOVED / RELOADED / WORLD_UNLOAD), `EncounterDeathSystem` (the precise defeat instant, one latch per run; a member death recorded), `EncounterDamageSystem` (observe-only on the INSPECT damage group, two empty-map lookups on the miss path), `EncounterTickSystem` (subject + members re-resolved, seeds stamped, presence credited, scale applied and reconciled after a phase, the chunk held ticking while the fight is open, wipe and timeout watched, marker moved) |
| `event/` | the six `IEvent<Void>` POJOs (`EncounterEngagedEvent`, `EncounterPhaseChangedEvent`, `EncounterDefeatedEvent`, `EncounterWipedEvent`, `EncounterResetEvent`, `EncounterSignalEvent`), `ResetReason`, and `Encounters`, the one `NativeEventSeam` instance the family fires through |
| `seam/` | `EncounterAttribution` (a non-player attacker's owner), `EncounterPowerSource` (the party's power), `EncounterSubjectSource` (who a player is to the engines that pay and notify), `EncounterRewardQueue` (the offline spool) and `EncounterSeams`, the holder that answers a posture and REPORTS ONCE when consulted unfilled (`EncounterSeamsTest` pins it). The root fills three from the progression runtime; power is a companion's to fill (mmo-mob-scaling fills it from its region power tracker at the subject's own position) |
| `payout/` | `EncounterLoot` (`Loot.OnDefeat` per credited participant through `LootEngine.select` then `RewardGrants.grantAll`, each roll kept with probability = share, offline participants queued; `Loot.OnPhase` spilled in world), `EncounterFeedback` (a moment per member behind the subject's own `FeedbackAudience`, `share` and `rank` as typed numbers), `EncounterDiscovery` (the world-map marker, placed, followed, removed) |
| `types/` | the six registered encounter `Type`s: `ZigGrant`, `ZigFeedback`, `ZigScaleTarget` (actions on `BuilderActionBase`/`ActionBase`), `ZigMembers`, `ZigFactor`, `ZigRested` (sensors on `BuilderSensorBase`/`SensorBase`; `ZigRested` takes no keys and is true while the entity carries no rest or the world clock is past it, the gate a summon leaf and a no-show timeout sit under so a resting site waits quietly), each gated to encounter contexts, registered by `EncounterTypes` through `NPCPlugin.registerCoreComponentType`. Builder keys are flat and array-ranged, the native builder vocabulary (`Count: [min, max]`), not the asset codecs' nested groups. Every action answers the engine FINISHED on every path, because a `false` is the engine's still-running and a blocking list waits on it (`ActionsAlwaysFinishTest` pins it) |
| `validate/` | `EncounterScriptScan` (a pure walk of a script's JSON, following `Reference` and resolving `Compute` reads), `EncounterScripts` (the loaded scripts read back off the builder manager, the cached does-it-author-engaged answer the tick asks, the role-exists probe and the spawn markers' role rosters), `EncounterValidator` (the findings, `ENCOUNTER_SCRIPT_ID_IS_ROLE_ID` among them: the engine keeps ONE builder per name across roles and scripts, so a script named after a role replaces it at load and the loser is unrecoverable from the builder map; the finding fires from what CAN be seen, a `RoleReference` that resolves to a loaded script, or a binding whose script id resolves to a loaded role), `EncounterPrefabAudit` (pure: a pack prefab's spawner block entries, a block type whose block entity carries `SpawnMarkerBlock`, read for the per-block `components` state the builder's prefab page and `/paste` need, since those write only the state the file carries while a hand placement and a `PrefabUtil` paste clone the type's template; `ENCOUNTER_PREFAB_SPAWNER_WITHOUT_STATE`, one per bare block, the prefab as the source), `EncounterAudit` (the once-gated boot pass at first player ready and `/zigencounter validate`; it also walks every non-core loaded pack's `Server/Prefabs/**.prefab.json` through the prefab audit, asking the loaded block types which are spawners; `addRoleNameSource(kind, supplier)` is how a store this module may not import, the placement engine, hands it the roles it names, wired by the root), `EncounterSelfTest` (the base and the example must resolve by index; SEVERE otherwise) |
| `command/` | `/zigencounter list | inspect <ref> | spawn <asset> [--world=] [--x= --y= --z=] | end <ref> | state <ref> <state> [--substate=] | validate | reload`, console-capable, engine-derived permission nodes; `EncounterRefs` resolves a run-id prefix or a script id off the live table |

## Shipped resources

- `Server/EncounterManager/Zc_Encounter_Base.json` (`Type: Abstract`): the skeleton every zc boss
  builds on as a `Variant`. Player sensor + `EncounterMembers` collector, a parameterised
  `SubjectSlot`, `TriggerSpawners` from `SpawnMarker` under `ZigRested`, the release on the Target sensor ALONE (bar,
  music, `zc:engaged`, never a role-sent beacon), a `FightBehavior` macro slot, the defeat beat with
  `ClearEncounterBossBar` + `zc:defeated`, the `Complete` re-arm with `zc:reset`.
- `Server/EncounterManager/Macros/{Zc_Phase_At_Health, Zc_Adds_Wave, Zc_Defeat_Beat}.json`
  (`Type: Component` on `Class: Instruction`, the vanilla macro shape with `_ImportStates`).
- `Server/EncounterManager/Zc_Encounter_Example.json`: spawnable, runs with no player and no boss,
  walks every beat and every Type, re-arms a few seconds after the defeat and loops until ended;
  the Player sensor with the `EncounterMembers` collector sits first as a `Continue` sibling, so a
  player who walks up counts while the story runs on without one. The headless gate's target
  (`/zigencounter spawn Zc_Encounter_Example`, then `end` it).
- `Server/ZiggfreedCommon/EncounterParticipation/Zc_Default.json`: the match-all credit rule.
- `Server/ZiggfreedCommon/Encounters/Zc_Encounter_Example.json`: the example's binding row.
- `Server/Languages/<locale>/ziggfreedcommon.encounter.lang`, nine locales: the boss bar's fallback
  name, the example's name, and the `/zigencounter` family under the `admin.` key family (so the
  keys resolve as `ziggfreedcommon.encounter.admin.<key>`, the same shape the progression and
  commerce admin files use).

## Conventions

- **Native first, absolutely.** Anything the design note calls native is authored in the script.
  A gap in native is owned here or named; it is never patched in Java "as a backstop".
- **The binding row re-states nothing the script says**, and the test enforces it. A knob that
  describes the fight belongs in the script; a knob that describes what the server owes for it
  belongs here.
- **Identity is never in a string.** Which encounter signalled is `EncounterManager.getEncounterId()`
  on the signalling entity; which subject a run has is a uuid re-resolved through the script's own
  slot, because an in-place role change reissues the boss's reference.
- **One `[encounter]` INFO line per run event, never per tick**, so a boot capture reads as a run's
  story.
- **Shares are normalised against the top contributor** (the top reads 1.0), so "loot rolls scale
  with share" pays the top contributor everything they won. Below `MinShare` is attempt credit only.
- **Reset is always the last event of a run**; a run that engaged and never concluded is settled as
  a wipe first.
- **The rest between fights is the library's, per site.** A manually triggered spawn marker never
  reads `SpawnAfterGameTime` (`SpawnMarkerEntity.trigger` checks `ManualTrigger` and a live spawn
  count only, and the count frees the instant the spawn dies), and the engine persists nothing of
  a script but its id and its `Rebind` slots (every load restarts it at `StartState` with fresh
  timers), so neither can hold a day. The row's `Timing.Rest` is stamped on the encounter entity at
  the defeat (`ZigEncounterRest`, codec-backed) on `WorldTimeResource`'s game time, and the script
  gates its summon and its no-show timeout on `ZigRested`; `Zc_Encounter_Base` does, so every
  Variant honours the knob. A world with no clock cannot rest and reads as rested.
- **An action always answers finished.** To the engine a `false` from an action's `execute` is
  "still running", and a blocking action list waits on it; a registered action with nothing to do
  says so in the log and answers true.
- **An open fight holds its chunk ticking, and so does an owned one from its spawn; a settled one
  lets go.** The tick resets the encounter chunk's active timer while the run is engaged and not
  settled, so `WipeGraceSeconds` and `MaxRunSeconds` are measured by the tick rather than cut short
  by a chunk going cold; a run spawned WITH an owner key (a round's boss, stood up at its arena
  while the party is still elsewhere) is held from the moment it is added, because its owner,
  difficulty, party and multiplier live only on the codec-less run and an unload would hand back a
  fresh, unowned one. An unowned run that has not engaged (a placed world boss, a console spawn) is
  not held. After the settlement the engine's own schedule takes over: the chunk unloads, the entity
  is stored with it, and the script re-arms from its start state on the next load.
- **A spawn into a chunk nobody stands in brings the chunk up first** (`spawnWhenLoaded`): the
  engine unloads an entity added into a chunk that is not ticking on the spot.
- **`Once` never heads a blocking list.** A leaf instruction re-runs its action list only while its
  sensor matches, and the engine spends a `Once` sensor after the instruction's first tick, so
  `Any` + `Once` over an `ActionsBlocking` list runs one action and strands the rest. A list that
  spans ticks (a `Timeout`, a wait) keeps a plain sensor and ends with the state change that stops
  it; the validator warns (`ENCOUNTER_ONCE_BLOCKS_LIST`). `Once` on a single-tick, non-blocking list
  is the vanilla idiom and stays.

## Tests

`EncounterEdgeTest` (the import ban, package by package), `BindingRestatesNothingTest` (the codec
keys against the script's vocabulary), `EncounterSignalTest` (the grammar), `ParticipationLedgerTest`
(the pure ledger), `EncounterScalingTest` (the clamp formula), `EncounterSeamsTest` (each seam reports
once, never when filled), `EncounterBindingCodecTest` (defaults, the phase-loot map, the owner overlay
leaf by leaf, the spec fold), `ParticipationRulesTest` (the two-axis ranking), `EncounterScriptScanTest`
(the shipped scripts as fixtures, macro and variant references, the defeat-beat rule),
`EncounterValidatorTest` (every finding code), `EncounterAdminKeysTest` (every spoken key ships),
`ActionsAlwaysFinishTest` (no registered action ever answers the engine still-running), `ZigEncounterRestTest` (the
sensor's three answers on the pure question behind it, and the rest component's codec round trip),
`EncounterPrefabAuditTest` (both prefab shapes, the per-block state a builder-page paste needs).
Everything ECS-bound (the systems, the spawner, the Types executing) is proven by the dev-server boot,
which a unit JVM cannot stand in for.
