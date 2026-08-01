# interaction/param/ - the fire-time parameter-fold seam

Router for `com.ziggfreed.common.interaction.param`. Two small doors plus a tiny SPI: how a
chain initiator attaches per-fire context to a firing chain, and how a number-bearing custom
interaction Type turns an authored `Base` into a per-caster value without common ever learning
what a "caster" means to the consumer.

## The two doors, and why the meta store is primary

A chain initiator (an ability cast, a station fire, ...) needs a way to tell every custom
interaction Type INSIDE that one chain execution - including every per-target fork a `Select`
step spins off - "here is who is casting, here is which scope, here is an opaque payload".
Two engine seams can carry that:

- **Primary: `InteractionContext`'s meta store** (`CastScope`/`CastScopes`). Typed, transient
  (never persisted, never round-trips through a codec), no string coercion. The load-bearing
  fact: `InteractionContext.duplicate()` copies the WHOLE meta store
  (`ctx.metaStore.copyFrom(metaStore)`), and a `Select` hit fork is exactly a `duplicate()` that
  only overwrites `TARGET_ENTITY`/`HIT_LOCATION` afterwards. Stash a scope once on the root
  context and every hit-leaf fork already has it - zero forwarding code needed anywhere in a
  consumer's own step handlers.
- **Secondary: the `InteractionVars` getter** (`CastScopes.applyVars`). Also copied by
  `duplicate()`, but String -> String only and drawn from ONE FLAT MAP spanning the whole
  execute/fork tree (no per-node scoping - prefix every var). This is for ASSET SELECTION
  (`Replace` slot picks), never for numbers.

See `CastScopes`'s class javadoc for the exact file:line evidence and reasoning; it is the
authoritative version of this argument.

## install() is a setup()-time call, never an eager static

`CastScopes` holds its `MetaKey<CastScope>` in a `private static volatile` field, assigned once
inside `install()` under a lock. Registering the key touches
`Interaction.CONTEXT_META_REGISTRY` (a public static field on `Interaction`), which forces
`Interaction`'s `<clinit>` - the RangeValidator/HytaleLogger trap that throws outside a live
server. `install()` MUST be called from the consumer plugin's `setup()`; every stash/read/clear/
applyVars call is a guarded no-op before that (and in a unit JVM, which never calls `install()`
at all). `install()` itself is idempotent and thread-safe - a second call is a harmless no-op,
which matters because the registry has no unregister and a duplicate registration would leak a
slot per call.

## ParamFold is per-consumer, ships identity by default

`ParamFold` follows the same per-consumer-instance idiom as `cast.OnHitRegistry`: a consumer
news up its own instance (one per parameter family, or one shared) rather than reaching for a
shared static, so two mods sharing this jar never see each other's resolvers. An un-configured
`ParamFold` is the IDENTITY fold - `resolve(...)` returns the authored base unchanged - so a
`ParamSlot` with no fold wired up (or a consumer that never calls `setResolver`) behaves exactly
like a plain constant. Every degradation path (no resolver, blank key, a null request, a
throwing resolver, a non-finite result) returns the base too - a `ParamFold` can never make a
number worse than "ignore the modifier and use what was authored".

## ParamSlot: the authorable `{Key, Base}` leaf, no validators

`ParamSlot` is the nested-group shape a number-bearing custom interaction Type field uses:
`"Damage": {"Key": "damage", "Base": 12.0}`, never flat `DamageBase`/`DamageKey` keys. Both
codec keys are wired `appendInherited` (not plain `append`) so a native `Parent` on the OWNING
Type actually inherits an untouched nested slot instead of silently dropping it - the same
reason `AbilityAsset`'s own nested groups (in the MMO jar) all use `appendInherited`.

`ParamSlot.codec()` is a LAZY, memoized holder-class accessor, never a `public static final
CODEC` field, so composing it into an owning Type's codec stays lazy end to end. It declares NO
validators on purpose: `Validators.min`/`range` construct a `RangeValidator`, the exact class
whose `<clinit>` reaches `HytaleLogger` and throws outside a live server. Range / sanity policy
for an authored `Base` belongs in the consumer's own content validator, never in this shared
leaf's codec.

## Files

- **[`CastScope`](CastScope.java)** - the immutable per-fire payload (scopeId, casterId,
  opaque payload, string var overlay, firedAtMillis). Field-additive, modelled on
  `cast.HitContext`.
- **[`CastScopes`](CastScopes.java)** - `install()`/`isInstalled()`/`stash()`/`read()`/
  `clear()`/`decorator()`/`applyVars()`. The context door.
- **[`ParamFoldRequest`](ParamFoldRequest.java)** - the immutable fold question (store, caster,
  scopeId/payload projected from an attached `CastScope`, paramKey, base). Field-additive.
- **[`ParamFoldResolver`](ParamFoldResolver.java)** - the consumer's `double fold(request)` SAM.
- **[`ParamFold`](ParamFold.java)** - the per-consumer fold instance + guard rail.
- **[`ParamSlot`](ParamSlot.java)** - the authorable `{Key, Base}` codec leaf + `resolve(...)`.

Common carries ZERO consumer vocabulary anywhere in this package: `scopeId` is an opaque
string, `payload` an opaque object, `paramKey` an opaque string. No ability, mastery, modifier
shape, or other domain concept is named here.
