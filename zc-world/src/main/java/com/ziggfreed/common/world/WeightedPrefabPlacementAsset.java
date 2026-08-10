package com.ziggfreed.common.world;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * A pack-authorable, mod-agnostic WEIGHTED prefab-placement entry, loaded from a
 * consumer's {@code Server/ZiggfreedCommon/PrefabPlacements/*.json} (one placement per file, PascalCase
 * filename; the path is supplied by the consumer at register time, so common hard-codes
 * no mod name). Each entry binds a prefab resolution key + a generic design-intent
 * {@link Role} + a candidate (x,z) + a selection weight - a single row of a weighted
 * placement table a level-design / arena builder seed-selects from purely as DATA.
 *
 * <p>Lifted from Kweebec's {@code StructurePlacementAsset} (its corrupted-ruin table),
 * generalized: the {@link Role} vocabulary stays generic level-design language
 * (COVER / CHOKEPOINT / BEACON / LANDMARK) and there are no baked-in defaults - a
 * consumer ships its own placements as pack JSON and (optionally) a Java baseline floor.
 *
 * <p><b>Pattern A - full structured asset</b> (mirrors common's {@code InstancePresetAsset}
 * and Kweebec's {@code StructurePlacementAsset} field-for-field). The engine decodes a
 * placement DIRECTLY into typed fields via {@link #CODEC} - the codec IS the single
 * schema authority on both the pack layer and an owner override layer.
 *
 * <p>Every {@code KeyedCodec} field name is PascalCase (the constructor rejects a
 * lower-case first letter at static init, throwing at server start).
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "Cover_House_NE", "PrefabKey": "MyMod/Cover_House",
 *   "Role": "cover", "X": 38.0, "Z": 30.0, "Weight": 1.0 }
 * }</pre>
 */
public final class WeightedPrefabPlacementAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, WeightedPrefabPlacementAsset>> {

    /**
     * The generic level-design intent of a placed prefab. A {@code DEFAULT} + a forgiving
     * {@link #fromString} so an unknown/blank authored {@code Role} degrades to the
     * default rather than failing the load. Deliberately generic vocabulary (no mod
     * coupling) so any minigame / dungeon / raid builder reuses the same labels.
     */
    public enum Role {
        /** A wall/structure a player can break line-of-sight behind. */
        COVER,
        /** A bridge/cluster that narrows movement (an enemy funnels through). */
        CHOKEPOINT,
        /** A lit landmark that draws the eye (a relight / objective beacon). */
        BEACON,
        /** A big, far structure that orients the player (a tower / elder ruin). */
        LANDMARK;

        /** The role chosen when none is authored. */
        public static final Role DEFAULT = COVER;

        @Nonnull
        public static Role fromString(@Nullable String s) {
            if (s == null || s.isBlank()) {
                return DEFAULT;
            }
            for (Role r : values()) {
                if (r.name().equalsIgnoreCase(s.trim())) {
                    return r;
                }
            }
            return DEFAULT;
        }
    }

    /** Default selection weight when none is authored. */
    private static final double DEFAULT_WEIGHT = 1.0;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String prefabKey;
    @Nullable private String role;
    private double x = 0.0;
    private double z = 0.0;
    private double weight = DEFAULT_WEIGHT;

    public static final AssetBuilderCodec<String, WeightedPrefabPlacementAsset> CODEC = AssetBuilderCodec.builder(
                    WeightedPrefabPlacementAsset.class,
                    WeightedPrefabPlacementAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Name is an optional human-readable echo of the asset key (the authoritative
            // key is the filename) - consumed by a no-op setter so it doesn't trip the
            // "Unused key(s)" warning, and emitted on encode.
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id already comes from the filename */ },
                    a -> a.id)
            .add()
            .append(new KeyedCodec<>("PrefabKey", Codec.STRING, false), (a, v) -> a.prefabKey = v, a -> a.prefabKey)
            .add()
            .append(new KeyedCodec<>("Role", Codec.STRING, false), (a, v) -> a.role = v, a -> a.role)
            .add()
            .append(new KeyedCodec<>("X", Codec.DOUBLE, false), (a, v) -> a.x = v, a -> a.x)
            .add()
            .append(new KeyedCodec<>("Z", Codec.DOUBLE, false), (a, v) -> a.z = v, a -> a.z)
            .add()
            .append(new KeyedCodec<>("Weight", Codec.DOUBLE, false), (a, v) -> a.weight = v, a -> a.weight)
            .add()
            .build();

    public WeightedPrefabPlacementAsset() {
    }

    /**
     * Build a placement in code (a consumer's optional {@code defaults} floor), without
     * going through the JSON {@link #CODEC}. A consumer's shipped {@code *.json}
     * placements author the same fields.
     *
     * @param id        the placement id (asset key)
     * @param prefabKey the prefab resolution key
     * @param role      the design-intent role (parsed forgivingly by {@link Role#fromString})
     * @param x         candidate world X
     * @param z         candidate world Z
     * @param weight    selection weight
     */
    @Nonnull
    public static WeightedPrefabPlacementAsset of(@Nonnull String id, @Nullable String prefabKey,
                                                  @Nullable String role, double x, double z, double weight) {
        WeightedPrefabPlacementAsset a = new WeightedPrefabPlacementAsset();
        a.id = id;
        a.prefabKey = prefabKey;
        a.role = role;
        a.x = x;
        a.z = z;
        a.weight = weight;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The prefab resolution key (e.g. {@code MyMod/Cover_House}), or {@code null} if unauthored. */
    @Nullable
    public String prefabKey() {
        return prefabKey;
    }

    /** The design-intent role, parsed forgivingly (an unknown/blank value -> {@link Role#DEFAULT}). */
    @Nonnull
    public Role role() {
        return Role.fromString(role);
    }

    /** Candidate world X. */
    public double x() {
        return x;
    }

    /** Candidate world Z. */
    public double z() {
        return z;
    }

    /** Selection weight (consumed by the weighted seeded pick in {@code WeightedPrefabPlacementConfig}). */
    public double weight() {
        return weight;
    }
}
