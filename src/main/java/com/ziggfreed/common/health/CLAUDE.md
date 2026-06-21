# health/ - native vital-stat restore (heal)

Router for `health/`. The mod-root `CLAUDE.md` PARADIGM applies: this is a generic,
mod-agnostic Hytale primitive (any minigame topping a player off - a win heal, a checkpoint
restore - needs it), so it lives here, not duplicated in a consumer.

- **[`HealthUtil`](HealthUtil.java)** - static helpers over the NATIVE `EntityStats` module, the
  SAME API the MMO Skill Tree health-tick path uses (`RegenTickingSystem` / `AbilityHealService`):
  read the `Health` / `Mana` `EntityStatValue` off the entity's `EntityStatMap`
  (`EntityStatsModule.get().getEntityStatMapComponentType()`, stat index via
  `DefaultEntityStatTypes.getHealth()`/`getMana()`) and `addStatValue(index, max - current)` to raise it.
  - `fullHeal(store, ref)` -> raise `Health` to max (a full heal); false if already full / no stat map.
  - `heal(store, ref, amount)` -> add `amount` to `Health`, engine-clamped to max (mirrors
    `AbilityHealService.applyInstant`).
  - `fullRestore(store, ref)` -> raise BOTH `Health` and `Mana` to max (each independently, so a
    mana-less entity still gets healed).
- **World thread only** (touches the `Store`); every method is try-guarded to a `false` return so a
  missing stat map / invalid ref / engine throw never escapes into the caller. The raw flogger LOGGER
  is itself wrapped in a try/catch (it throws in a log-manager-less unit JVM).
- Exemplar consumer: Kweebec Nightmare's `RoundService.scheduleOverworldResync` calls `fullHeal` when
  a player returns to the overworld after a round WIN.
