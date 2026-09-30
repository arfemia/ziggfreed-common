# codec/

- The engine's `MapCodec` and array codecs are not `InheritCodec`s: under `Parent` a child that authors the field replaces it whole. Use `InheritMapCodec` for a per-key overlay, and key a collection by identity when one entry must stay overridable.
- Author an offset or a rotation with `Vec3` (doubles), `Vec3i` (block cells; a decimal fails the decode) or `Rotation` (degrees), never the engine's `Vector3dUtil.CODEC` or `Rotation3f.CODEC`: those carry primitive axes, reject partial authoring and read radians.
- `DeferredCodec` stands in for a codec built from startup registrations, or one that must not class-load in a unit JVM (`ItemStack.CODEC`). It forwards `InheritCodec`, so the field still merges under `Parent`.
- `JsonTreeCodec` and `ScalarStringCodec` keep a number's authored spelling (`10` stays `10`) for a second decode; use them only for a schema-less subtree or a params map, never a field with a known shape.
- `JsonParentResolver` is only for a family merging across the store and owner-dir layers, and a `Where` group goes in its `replaceKeys`; a family living in one store uses native `Parent`.
- `Tags` is a field `AssetBuilderCodec` already declares; declaring it again throws at static init, so a tag list of your own takes another key (`ArenaTags`).
