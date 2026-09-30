# interaction/param/ - per-fire context and the parameter fold

- Per-fire context rides the `InteractionContext` meta store through `CastScopes`: `duplicate()` copies the whole meta store, so every `Select` hit fork inherits a stashed `CastScope` with no forwarding code. Stash it before the chain queues, with `CastScopes.decorator(scope)` passed to `NativeChainFire`'s decorator overload.
- `CastScopes.applyVars` (the `InteractionVars` getter) is String to String in one flat map across the whole execute tree: prefix every var, and use it only for asset selection (`Replace` picks), never for numbers.
- Call `CastScopes.install()` from the consumer plugin's `setup()`, never from a static initializer: registering the `MetaKey` runs `Interaction`'s `<clinit>`, which throws outside a live server. Every call before `install()` is a no-op, and `install()` is idempotent.
- `ParamFold` is a per-consumer instance, never a shared static. With no resolver it is the identity, and every failure path (blank key, throwing resolver, non-finite result) returns the authored base.
- A number-bearing Type field is a nested `ParamSlot` group (`"Damage": {"Key": ..., "Base": ...}`), never flat `DamageKey`/`DamageBase` keys. Its keys are `appendInherited`, `codec()` is lazy, and it declares no validators (`RangeValidator`'s `<clinit>` throws outside a server), so range checks belong in the consumer's content validator.
- `scopeId`, `payload` and `paramKey` are opaque: never name a consumer concept in this package.
