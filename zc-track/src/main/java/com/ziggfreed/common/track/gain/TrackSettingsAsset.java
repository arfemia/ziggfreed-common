package com.ziggfreed.common.track.gain;

import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server's one word on how every track's gains are paid, beside what each track's own file says: today, when a
 * player counts as idle ({@code Idle.Minutes}), after which the {@value GainFactors#IDLE} factor pays a track naming it
 * nothing. The FILE NAME is the record's id, and the record every gain reads is {@value #SHARED_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/TrackSettings/Default.json}, which this library ships; a pack's same-id
 * file wins by pack order, and the owner layer is {@code mods/ziggfreedcommon/track-settings.json} (the owner menu's
 * Track Settings screen writes it):
 * <pre>{@code
 * { "Default": { "Idle": { "Minutes": 20 } } }
 * }</pre>
 */
public final class TrackSettingsAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackSettingsAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackSettings";

    /** The id of the record every gain reads (the file is {@code Default.json}; ids fold lower-case). */
    public static final String SHARED_ID = "default";

    /** The owner file. */
    public static final String FILE = "track-settings.json";

    /** The file's id as an owner form names it: its path under {@code mods/}. */
    public static final String BINDING = "ziggfreedcommon/" + FILE;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Idle idle;

    public static final AssetBuilderCodec<String, TrackSettingsAsset> CODEC = AssetBuilderCodec.builder(
                    TrackSettingsAsset.class,
                    TrackSettingsAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Idle", Idle.CODEC, false),
                    (a, v) -> a.idle = v, a -> a.idle, (a, p) -> a.idle = p.idle)
            .documentation("When a player counts as idle: after this long without moving, looking around or using "
                    + "anything, every track whose Gain.Factors names Idle (every track that writes no Factors "
                    + "list does) pays them nothing, until they move again.").add()
            .build();

    /** The Idle group. */
    public static final class Idle {

        @Nullable protected Integer minutes;

        public static final BuilderCodec<Idle> CODEC = BuilderCodec.builder(Idle.class, Idle::new)
                .appendInherited(new KeyedCodec<>("Minutes", Codec.INTEGER, false),
                        (o, v) -> o.minutes = v, o -> o.minutes, (o, p) -> o.minutes = p.minutes)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How many minutes without moving, looking around or using anything before a "
                        + "player's gains stop. 0 or unauthored never stops them.").add()
                .build();

        public Idle() {
        }
    }

    public TrackSettingsAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** How long, in milliseconds, before a player counts as idle; 0 when they never do. */
    public long idleMillis() {
        Integer minutes = idle == null ? null : idle.minutes;
        return minutes == null || minutes <= 0 ? 0L : minutes * 60_000L;
    }

    /** The {@code defaults < pack < owner} fold, and its owner file. */
    public static final class Config extends AbstractKeyedAssetConfig<TrackSettingsAsset> {

        private static final Config INSTANCE = new Config();

        /** What a server with no record reads: every leaf unauthored (never idle). */
        private static final TrackSettingsAsset DEFAULTS = new TrackSettingsAsset();

        @Nonnull
        private static volatile Path directory = OwnerFiles.DIRECTORY;

        private Config() {
        }

        @Nonnull
        public static Config getInstance() {
            return INSTANCE;
        }

        @Override
        public synchronized void mergePackLayer(@Nonnull Map<String, TrackSettingsAsset> layer) {
            super.mergePackLayer(layer);
            GainPlans.invalidateAll();
        }

        @Override
        public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackSettingsAsset> layer) {
            super.mergeOwnerLayer(layer);
            GainPlans.invalidateAll();
        }

        /** The folded record every gain reads, or the defaults when no layer authored one. */
        @Nonnull
        public TrackSettingsAsset settings() {
            return resolveOrDefault(SHARED_ID, DEFAULTS);
        }

        /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
        public static void setDirectory(@Nonnull Path dir) {
            directory = dir;
        }

        /** Where the owner's file lives right now. */
        @Nonnull
        public static Path file() {
            return directory.resolve(FILE);
        }

        /** (Re)read {@code track-settings.json} into the fold's owner layer. */
        public static void reloadOwnerLayer() {
            OwnerLayerReader.apply("track", file(), TrackSettingsAsset.class, CODEC, INSTANCE, "track settings");
        }
    }
}
