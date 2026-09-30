# health/

- `HealthUtil.scaleMaxHealth` is add-only: it no-ops while its keyed MAX modifier exists and heals to the new max exactly once, which `EncounterScaling`'s first apply relies on. `reconcileMaxHealth` converges that modifier to the current factor without re-maximizing, for a spawn hook that re-derives the scale on chunk reload. Never fold one into the other.
- Both have `Holder` overloads for a pre-add `onEntityAdd` spawn hook, where no `Ref` exists yet.
