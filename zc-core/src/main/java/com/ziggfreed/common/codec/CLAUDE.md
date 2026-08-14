# codec/ - inheritance-aware codec + raw-JSON merge primitives + shared codec leaf types

Router for `com.ziggfreed.common.codec`. The mod-agnostic pieces of native(-lite) asset `Parent` inheritance a consumer's asset family composes, plus the shared authorable LEAF TYPES any consumer's asset codec embeds; pure logic over the engine codec API + Gson, no consumer domain types.

- **[`Vec3`](Vec3.java)** - the ONE spatial-offset leaf: a nested `{X, Y, Z}` group of independently
  NULLABLE doubles matching the engine's own `Vector3d` leaf NAMES. Embed via
  `new KeyedCodec<>("Offset", Vec3.CODEC, false)`; each consumer documents its own frame/units at
  its own accessor. Deliberately minted rather than reusing the engine's `Vector3dUtil.CODEC`: that
  codec's JOML carrier has PRIMITIVE axes (unauthored becomes an authored-looking `0.0`, erasing
  null-means-inherit overlay granularity) and per-axis non-null validators that REJECT partial
  authoring (`"Offset": {"Y": -0.1}`). Lifted from the rpg-stations schema wave (2026-08-05).
- **[`Rotation`](Rotation.java)** - the ONE rotation leaf: `{Yaw, Pitch, Roll}` independently
  nullable DEGREES, the engine's own rotation vocabulary (it reserves `{X,Y,Z}` for POSITION).
  Consumers convert to radians once at their own apply site (engine Y-X-Z intrinsic euler order).
  NOT `Rotation3f.CODEC` (radians in floats, NaN sentinel - reusing it would silently change every
  shipped value's authoring unit). Lifted with `Vec3`.
- **[`TagMatch`](TagMatch.java)** - the ONE native-tag match leaf: the
  `{"<tagFamily>": ["<value>", ...]}` map codec (`TagMatch.CODEC`, a stateless shared instance)
  plus the case-insensitive ANY-of matcher (`matches`) and the `isAuthored`/`empty` helpers. The
  map is ONE leaf for inherit/overlay purposes (authoring it replaces the whole map; the stock
  `MapCodec` is not an `InheritCodec` - see `InheritMapCodec` below when per-key merge is wanted
  on a FIELD under native `Parent`). Lifted with `Vec3`.

- **[`JsonTreeCodec`](JsonTreeCodec.java)** - captures an authored JSON subtree VERBATIM, as a value
  the owning codec hands on without interpreting: the one field type for "whatever the author wrote
  here is a template someone else fills in later". Numbers keep their authored SPELLING (`10` stays
  `10`, never `10.0`) because the captured tree is re-emitted as JSON and decoded a second time
  through a codec whose field may be an integer; object key order is preserved for the same reason a
  diff reads better when it matches the file. Two shapes so the in-game editor knows what to expect
  (`object()` / `array()`); both decode any JSON value, the difference is documentation not
  enforcement. Reach for it ONLY where a subtree genuinely has no schema at this level - a field
  with a known shape is a nested codec, which is what gets validation, inheritance and editor
  support. Shipped callers today: a quest generator's `Child` body and its axis `Values`.
- **[`InheritMapCodec`](InheritMapCodec.java)** - a generic string-keyed map `Codec` that participates in native asset `Parent` inheritance with per-KEY merge (the stock `MapCodec` whole-replaces on inherit because it is not an `InheritCodec`). On a child overlay it seeds every parent entry, deep-merges each child-provided key when the value codec is itself an `InheritCodec` (e.g. a `BuilderCodec` sub-object), and retains parent-only keys. Use for any keyed-map FIELD that should overlay by key under engine-native inheritance (the dialogue engine's `Nodes` map is the exemplar consumer; `npc/placement`'s `Interact.Bindings` is the second, and its javadoc states the general rule: an ARRAY leaf under `appendInherited` is a SINGLE leaf, so a child authoring it at all drops every inherited entry - key a collection by its own identity and use this codec whenever per-entry override matters).
- **[`JsonParentResolver`](JsonParentResolver.java)** - generic parent-chain resolution over a keyed RAW-JSON pool: the pre-decode half of native-lite `Parent` inheritance for an asset family whose bodies must merge ACROSS layers the engine store cannot see (jar + pack store bodies PLUS owner-dir files read off disk; the engine's own `Parent` resolution runs only inside one store's load batch). `resolve(pool, outputIds, parentKey, warn)` resolves each requested id transitively (memoized, cycle-guarded; a cycle / unknown parent warns and resolves standalone), deep-merging the child OVER the resolved parent per leaf via `util/JsonTreeUtil.deepMergeInto` (object keys merge recursively, primitives + ARRAYS replace wholesale) and stripping the parent key; ids + parent refs lower-cased; a pool body not in `outputIds` is a shared BASE that is never emitted. The 5-arg overload takes `replaceKeys`: TOP-LEVEL keys a child authoring them REPLACES wholesale instead of deep-merging - for a group that is ONE predicate whose sub-keys are alternatives, a `Where` world selector above all (mob-scaling passes `Set.of("Where")`, matching the rule the engine-native decode applies to `WorldSelector`'s leaves); a child omitting such a key still inherits the parent's copy whole. The consumer decodes each resolved body through its ONE structured `BuilderCodec` afterwards. The mob-scaling mod's per-world `Worlds/*.json` fold is the first consumer; an asset family whose bodies all live in ONE store wants the engine's own `Parent` resolution instead, not this. Unit-tested (`JsonParentResolverTest`).
