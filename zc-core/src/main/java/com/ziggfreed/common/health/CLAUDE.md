# health/

- `HealthUtil.scaleMaxHealth` is add-only: it no-ops while its keyed MAX modifier exists and heals to the new max exactly once, which `EncounterScaling`'s first apply relies on. `reconcileMaxHealth` converges that modifier to the current factor without a second heal, for a spawn hook that re-derives the scale on chunk reload. Never fold one into the other.
- Heal after a modifier write only through `fillToMax` (an Add by the gap), never `EntityStatMap.maximizeStatValue`: on a shared stat the engine rewrites the queued `PutModifier` into the Maximize (`EntityStatMap.tryMergeUpdate`), so viewers never get the modifier and see an empty bar on a living mob (`HealthFillTest`). Any family code filling a raised pool calls it, as zc-dialogue's `applyFortify` does.
- Both have `Holder` overloads for a pre-add `onEntityAdd` spawn hook, where no `Ref` exists yet.
- Every put goes through `wholeMaxFactor`, so a scaled max is a whole number: damage lands in whole points, and a fractional max leaves a living mob on a sliver a rounded readout shows as 0. Keep any new scale seam on it.
