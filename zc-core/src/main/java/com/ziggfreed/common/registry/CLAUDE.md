# registry/

- Every open registry keeps its map in `RegistryLedger`; never grow a parallel map plus a warn-once set.
- `put` replaces and warns once per id, by identity (re-registering the same instance is silent). Use `putQuietly` only when the caller logs a better line for that swap, and `putIfAbsent` only for a singular slot where the first claim wins.
- Import `RegistrationInfo` through `RegistryLedger`: Java will not import the nested type through a subclass such as `PlacementRegistryLedger`.
