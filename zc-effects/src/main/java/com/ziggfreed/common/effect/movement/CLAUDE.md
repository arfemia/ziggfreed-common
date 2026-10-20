# effect/movement/ - speed and knockback on the engine

- The one applier of the `MoveSpeed` and `KnockbackResist` stats (R706), shaped like `effect/resist/`: `MovementSyncSystem` keeps each living player's `Zig_Movement_Speed_<pct>` (native `HorizontalSpeedMultiplier`, held to -90 and `MoveSpeedMax`) and `Zig_Movement_Knockback_<pct>` (native `KnockbackMultiplier`, held to `KnockbackResistMax`) in step with the stats every tick, through `RuntimeEffectGrid.carriesExactly` then a reconcile only on a difference.
- A consumer raises the stats and never applies these effects.
