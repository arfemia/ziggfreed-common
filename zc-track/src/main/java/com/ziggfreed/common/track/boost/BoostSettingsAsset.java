package com.ziggfreed.common.track.boost;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The server's one word on how boosts add up and how many may run: the cap every boost on a track sums to, whether
 * only the strongest of each scope counts, and how many a player and the server may have running at once. The FILE
 * NAME is the record's id, and the record every boost reads is {@value #SHARED_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/BoostSettings/Default.json}, which this library ships with the
 * defaults below; a pack's same-id file wins by pack order, and the owner layer is
 * {@code mods/ziggfreedcommon/boost-settings.json}:
 * <pre>{@code
 * { "Default": { "Cap": 300 } }
 * }</pre>
 */
public final class BoostSettingsAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, BoostSettingsAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/BoostSettings";

    /** The id of the record every boost reads (the file is {@code Default.json}; ids fold lower-case). */
    public static final String SHARED_ID = "default";

    /** The most every boost on one track adds up to, as a percent, unless the record says otherwise (R669). */
    public static final double DEFAULT_CAP = 200.0;

    /** How many personal boosts one player may have running at once, unless the record says otherwise. */
    public static final int DEFAULT_PERSONAL_LIMIT = 3;

    /** How many server boosts may run at once, unless the record says otherwise. */
    public static final int DEFAULT_SERVER_LIMIT = 3;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Double cap;
    @Nullable protected Boolean highestOnly;
    @Nullable protected Limits limits;

    public static final AssetBuilderCodec<String, BoostSettingsAsset> CODEC = AssetBuilderCodec.builder(
                    BoostSettingsAsset.class,
                    BoostSettingsAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Cap", Codec.DOUBLE, false),
                    (a, v) -> a.cap = v, a -> a.cap, (a, p) -> a.cap = p.cap)
            .metadata(EditorSchema.defaultValue(DEFAULT_CAP))
            .documentation("The most every boost on one track adds up to, as a percent: with 200, a +100% server "
                    + "boost beside a +150% personal one pays +200%, three times a gain. 200 when unauthored; 0 "
                    + "turns every boost off. A mod's own bonus is not counted against it.").add()
            .appendInherited(new KeyedCodec<>("HighestOnly", Codec.BOOLEAN, false),
                    (a, v) -> a.highestOnly = v, a -> a.highestOnly, (a, p) -> a.highestOnly = p.highestOnly)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True counts only the strongest server boost and the strongest personal boost on a "
                    + "track, added together, rather than every boost running. Unauthored means false.").add()
            .appendInherited(new KeyedCodec<>("Limits", Limits.CODEC, false),
                    (a, v) -> a.limits = v, a -> a.limits, (a, p) -> a.limits = p.limits)
            .documentation("How many boosts may run at once: a player's own, and the server's. A start past either "
                    + "is refused, and a boost item is kept.").add()
            .build();

    public BoostSettingsAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The cap as a percent, 0 or more; {@link #DEFAULT_CAP} when unauthored. */
    public double cap() {
        return cap == null || !Double.isFinite(cap) ? DEFAULT_CAP : Math.max(0.0, cap);
    }

    /** Does only the strongest boost of each scope count? */
    public boolean highestOnly() {
        return Boolean.TRUE.equals(highestOnly);
    }

    /** How many personal boosts one player may run at once, 0 or more. */
    public int personalLimit() {
        Integer limit = limits == null ? null : limits.personal;
        return limit == null ? DEFAULT_PERSONAL_LIMIT : Math.max(0, limit);
    }

    /** How many server boosts may run at once, 0 or more. */
    public int serverLimit() {
        Integer limit = limits == null ? null : limits.server;
        return limit == null ? DEFAULT_SERVER_LIMIT : Math.max(0, limit);
    }

    /** The Limits group: how many boosts may run at once. */
    public static final class Limits {

        @Nullable protected Integer personal;
        @Nullable protected Integer server;

        public static final BuilderCodec<Limits> CODEC = BuilderCodec.builder(Limits.class, Limits::new)
                .appendInherited(new KeyedCodec<>("Personal", Codec.INTEGER, false),
                        (o, v) -> o.personal = v, o -> o.personal, (o, p) -> o.personal = p.personal)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_PERSONAL_LIMIT))
                .documentation("How many personal boosts one player may have running at once. 3 when "
                        + "unauthored; 0 lets nobody start one.").add()
                .appendInherited(new KeyedCodec<>("Server", Codec.INTEGER, false),
                        (o, v) -> o.server = v, o -> o.server, (o, p) -> o.server = p.server)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_SERVER_LIMIT))
                .documentation("How many server boosts may run at once. 3 when unauthored; 0 lets nobody start "
                        + "one.").add()
                .build();

        public Limits() {
        }
    }
}
