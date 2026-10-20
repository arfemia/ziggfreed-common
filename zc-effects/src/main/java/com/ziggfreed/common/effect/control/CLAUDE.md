# effect/control/ - the family's crowd control (R740)

- `StunControl` (stun, stagger, wake, the `Immune` and `Immunity` ramp) and `AggroControl` (taunt, drop, suppress), with the `ZigStun`, `ZigTaunt`, `ZigAggroDrop` and `ZigAggroSuppress` Types.
- A stun is the native `Zig_Stunned` or `Zig_Staggered` effect (`Parent: Stun`) and nothing else is kept: query and wake read the effect.
- An aggro window lives in the world's `AggroWindows` resource, never on the source entity; its attitudes go through the world's `AttitudeView` provider (`AggroAttitudes`), never a write into a creature's override memory, and `AggroSweepSystem` only edits fields of components a creature already has.
