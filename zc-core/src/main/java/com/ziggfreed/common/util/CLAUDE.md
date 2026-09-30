# util/

- A mod's logging facade wraps one `GuardedLogger`; command-interactions keeps its own hand-rolled `Log` on purpose (it takes no library dependency), so never converge it.
- An authored command line (a reward, a drop, a grant) goes through `command/CommandRunner` (placeholders, the `/give --quantity=N` fix, a guarded failure sink); call `CommandExecutor` only for a fixed command the mod composed itself.
- `NumberFormatter` output is only for text no client format can express (a compact `446k`, a console line); a number a player reads binds as a typed param (`Msg.num`). The root `NumberDisplayHygieneTest` catches `raw(NumberFormatter...)` on one line only, so a formatted string built on another line slips past it.
- A mob is identified by its NPC role (`EntityIdentifierUtil.getMobId`), never by the model it wears: unrelated roles can share one model.
- Weighted picks go through `WeightedPick`: a negative weight never picks, a zero weight is stepped over, an all-zero set picks uniformly, and `one()` consumes exactly one sample.
- Never repoint an already-shipped `SplitMix64` stream at `mix` unless the result is bit-identical.
- `PeriodMath` is the one repeating-window authority (UTC `floorDiv`; `millisUntilNext` is always positive; `nextBoundaryMs` saturates at `Long.MAX_VALUE`); quest repeats and shop rotations both delegate to it.
- Every `mods/ziggfreedcommon/*.json` owner file follows `OwnerFiles`: a `$`-prefixed key is never an entry id, and a file declaring a newer `$SchemaVersion` is refused whole.
- `JsonOverrideWriter` never overwrites a malformed file, and the caller owns number type fidelity (`Integer` for an integer codec, `Double` for a double one).
- `AssetIndexCache` never caches `0` or a sentinel (index 0 is many maps' none slot); `DamageCauseCache` caches any index `>= 0`; both retry an unresolved id. A stat channel uses zc-entity's `StatIndexCache`, since a custom channel may sit at index 0.
