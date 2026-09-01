# interaction/type/ - the custom interaction-Type toolkit

Router for `com.ziggfreed.common.interaction.type`. The plumbing a consumer's own custom
interaction `Type`s (an `MmoSelect`, an `MmoAbilityDamage`, ...) need: clinit-safe
registration, null-safe context accessors, and the two documented chain-state conventions a
`firstRun` resolves through. This package holds NO Type of its own - Types are authored by the
consumer (the MMO), this package only supplies the toolkit they're built from.

## The clinit trap (read this before touching any of these files)

Forcing `Interaction`'s class-init outside a live server throws: `Interaction`'s own static
initializer transitively reaches a `RangeValidator` whose `<clinit>` touches `HytaleLogger`,
which throws `IllegalStateException("Log manager wasn't set!")` unless the Hytale server has
already installed `HytaleLogManager` as the JVM's log manager (documented at
`additional-mods/command-interactions/src/main/java/com/ziggfreed/interactioncommands/
interaction/RunCommandInteraction.java:127-137`). Every class here is built around not
touching `Interaction.CODEC` / `Interaction.ABSTRACT_CODEC` / `Interaction.CONTEXT_META_REGISTRY`
until a live server is present:

- **[`InteractionTypeSpec`](InteractionTypeSpec.java)** holds a `Supplier<? extends
  BuilderCodec<? extends Interaction>>`, never the codec itself, and never invokes it - not in
  the constructor, not in `toString()`. This is what makes a spec constructible and listable in
  a unit JVM. **The convention it implies for a Type author**: a Type exposes {@code static
  BuilderCodec<X> codec()} (a lazy holder method), NEVER a `public static final CODEC` field -
  a `static final` initializer runs at class-load, a lazy holder method runs only when called.
- **[`InteractionTypes`](InteractionTypes.java)** is the ONLY class in this package allowed to
  call `spec.codecSupplier().get()`, and only inside `register`/`registerAll`, which a consumer
  calls once from `setup()` on a live server. The argument guards (null plugin/spec, blank
  typeName) run and return `false` BEFORE `Interaction.CODEC` is referenced at all - this
  ordering is load-bearing, not incidental, because it is what lets the unit tests pass null
  arguments without forcing the clinit.
- Pinned engine signature: `PluginBase.getCodecRegistry(Interaction.CODEC)` resolves to the
  ASSET overload returning `CodecMapRegistry.Assets<Interaction, ?>`, whose `register` is
  `register(@Nonnull String id, Class<? extends Interaction> aClass, BuilderCodec<? extends
  Interaction> codec)`.

## Silent skip vs Failed - [`InteractionOutcome`](InteractionOutcome.java) / [`InteractionBody`](InteractionBody.java)

Every custom Type's `firstRun` resolves the chain state through exactly one of these; picking
the wrong one is a behavior bug, not a cosmetic one:

| convention | resolves | chained native step | when |
|---|---|---|---|
| `skip(ctx)` | `Finished` | **runs** | a gate miss - cooldown still running, chance roll lost, no permission, no player on the firing entity. The chain continues exactly as if the step were absent (a weapon still swings, a consumable still consumes). The `RunCommandInteraction` convention. |
| `finished(ctx)` | `Finished` | **runs** | the work actually ran. Same resolved state as `skip`, different call-site intent (documents "I did the work" vs "I chose to skip"). |
| `failed(ctx)` | `Failed` | **suppressed** | a hard internal error (null CommandBuffer, unresolvable target, an uncaught throw) OR a deliberate branch to the chain's Failed label. This is the free-refund mechanism a cast-then-consume scroll relies on: a failed cast never reaches the chained `ModifyInventory`. |

`guard(ctx, label, body)` is the structural wrapper: it runs an `InteractionBody` (allowed to
throw), resolves `Finished`/`Failed` from the boolean result, and turns any thrown `Throwable`
into a guarded WARN plus `Failed` - so a Type author can never forget to write the state (a
missing state write leaves the client spinning forever on a server-waiting node). The body runs
even with a null `ctx` (only the state write is skipped), which is what makes it unit-testable.

A Type able to resolve `Failed` must return `WaitForDataFrom.Server` from `getWaitForDataFrom()`
(the `SimpleInteraction` contract) - it costs a round trip at that node. A Type that only ever
resolves `Finished` may stay `None`. Decision 38 (the ability-system-rework design authority in
the hyMMO monorepo) binds ability BODIES to Server/None only.

## Owner vs target - [`InteractionCtx`](InteractionCtx.java)

Every accessor is static, guarded, and `@Nullable`-returning: a null context, a missing
component, an invalid ref, or any engine throw all degrade to `null` plus a guarded FINE - never
a throw into the caller.

The one fact worth internalizing: **inside a hit leaf, `owner` is the caster and `target` is the
swept entity**, because a Selector's `HitEntity` fork does `context.duplicate()` - which carries
the ORIGINAL owning/running entity forward unchanged - and only overwrites the meta-store's
`TARGET_ENTITY` (`SelectInteraction.java:310-312`). `owner(ctx)` resolves `getOwningEntity()` when
valid, else falls back to `getEntity()` (the firing entity), else null - the same pair the
engine's own `DamageEntityInteraction` resolves.

`position`/`lookDirection` return DEFENSIVE COPIES (the engine's `TransformComponent`/
`HeadRotation` return live, mutable vectors); `eyePosition` is `position` raised on Y by a
clamped-non-negative height. `player(ctx, ref)` fetches the `PlayerRef` COMPONENT - never the
deprecated `Player.getPlayerRef()`. `world(ctx)` is `buffer(ctx).getExternalData().getWorld()`.

World-thread throughout (every accessor reads a live component off a `CommandBuffer`).
