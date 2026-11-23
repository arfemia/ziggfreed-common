package com.ziggfreed.common.resource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonValue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.RawAsset;
import com.hypixel.hytale.assetstore.codec.ContainedAssetCodec;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.asset.AssetStoreWriter;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * A pool's owner layer written onto the native stat itself: a stat a cost spends (Energy, or a pack's own pool) keeps
 * its maximum and its refill in its own file ({@code Server/Entity/Stats/<Id>.json}), and a server owner retunes both
 * on the Stat Pools screen of the owner menu, saved in {@code mods/ziggfreedcommon/stat-pools.json} ({@link #BINDING},
 * {@link StatPoolAsset}). The engine then reads the stat as it reads any other: every living entity's pool takes the
 * new maximum and refill on the store's own reload, with nothing of ours read per tick or per cast.
 *
 * <p><b>Which stats.</b> A stat whose file carries the native tag {@value #TAG} ({@code "Tags": { "ZigOwnerPool":
 * [ "true" ] }}), so a pack opts its own pool in with one line in its own file and nothing is listed here. Its shipped
 * {@code Max}, and the refill its first {@code Regenerating} rule gives ({@code Amount} over {@code Interval}), are the
 * layer under the owner's ({@link StatPoolConfig}).
 *
 * <p><b>How it lands.</b> At every load of the stat store the shipped file of each tagged stat is kept as written (read
 * from its pack, never from the copy written here), the owner file is read again, and a pass on the asset writer's
 * thread ({@link AssetStoreWriter#offThread}) writes each pool whose values in force differ from the loaded stat's, as
 * that file with its {@code Max} (and an {@code InitialValue} that started full, so it still does) and its first rule's
 * {@code Amount} replaced, under the pack {@value #OWNER_PACK}. A pool back at its shipped values is written back as
 * shipped, never removed, so the stat's index never moves. The stat's other leaves, its conditions and tags stand.
 */
public final class StatPools {

    /** The native stat tag that lists a pool on the owner's screen. */
    public static final String TAG = "ZigOwnerPool";

    /** The pack the owner's values are written under, beside the pack that ships the stat. */
    public static final String OWNER_PACK = "ziggfreedcommon-stat-pools";

    /** The owner file, as an owner form's {@code File} names it. */
    public static final String BINDING = "ziggfreedcommon/stat-pools.json";

    /** The owner file's name in {@link OwnerFiles#DIRECTORY}. */
    public static final String FILE = "stat-pools.json";

    /** A refill rule written where a file had none (an owner gave a pool with no refill one). */
    static final double DEFAULT_INTERVAL = 0.1;

    /** How close two numbers must be to count as the same value (the engine holds floats). */
    private static final double EPSILON = 1e-4;

    private static final String LOG_TAG = "pools";

    /** A tagged stat's file as its own pack ships it, and the two numbers it gives. */
    record Shipped(@Nonnull String id, @Nonnull JsonObject json, double max, double initialValue, double regenPerSecond,
            double interval) {
    }

    /** By exact stat id: every tagged stat as shipped, kept across loads of the owner's own writes. */
    private static final Map<String, Shipped> SHIPPED = new ConcurrentHashMap<>();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private StatPools() {
    }

    /** Point the owner file at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner file is read from and written to. */
    @Nonnull
    public static Path file() {
        return directory.resolve(FILE);
    }

    /**
     * The owner file as an in-game editor may write it: its Default read off the shipped values, its reload
     * {@link #reload}.
     */
    @Nonnull
    public static OwnerFileBinding binding() {
        return OwnerFileBinding.keyedStore(BINDING, StatPools::file, StatPoolConfig.getInstance(), StatPoolAsset.CODEC,
                StatPools::reload);
    }

    /**
     * The stat store loaded (at boot, or a pack or this class wrote a stat): keep each tagged stat's shipped file,
     * fold the owner file over them, and have the values in force written on the asset writer's thread. Never throws.
     */
    public static void onStatsLoaded(
            @Nonnull LoadedAssetsEvent<String, EntityStatType, IndexedLookupTableAssetMap<String, EntityStatType>> event) {
        try {
            IndexedLookupTableAssetMap<String, EntityStatType> map = event.getAssetMap();
            for (Map.Entry<String, EntityStatType> loaded : event.getLoadedAssets().entrySet()) {
                String id = loaded.getKey();
                EntityStatType stat = loaded.getValue();
                if (id == null || stat == null || OWNER_PACK.equals(map.getAssetPack(id))) {
                    continue;
                }
                if (!tagged(stat)) {
                    SHIPPED.remove(id);
                    continue;
                }
                JsonObject json = shippedJson(map, id, stat);
                if (json == null) {
                    SafeLog.warn("[" + LOG_TAG + "] the stat '" + id + "' is tagged " + TAG + " but its file could not"
                            + " be read, so the owner's Stat Pools screen leaves it as shipped");
                    SHIPPED.remove(id);
                    continue;
                }
                SHIPPED.put(id, shippedOf(id, json, stat));
            }
            Map<String, StatPoolAsset> layer = new LinkedHashMap<>();
            for (Shipped shipped : SHIPPED.values()) {
                layer.put(shipped.id(), StatPoolAsset.of(shipped.id(), shipped.max(), shipped.regenPerSecond()));
            }
            StatPoolConfig.getInstance().mergePackLayer(layer);
            reload();
        } catch (Throwable t) {
            SafeLog.warn("[" + LOG_TAG + "] the tunable pools could not be read off the stats that loaded", t);
        }
    }

    /** Read the owner file again and write the values in force onto the stats. Any thread; never throws. */
    public static void reload() {
        try {
            OwnerLayerReader.apply(LOG_TAG, file(), StatPoolAsset.class, StatPoolAsset.CODEC,
                    StatPoolConfig.getInstance(), "stat pool");
        } catch (Throwable t) {
            SafeLog.warn("[" + LOG_TAG + "] " + file() + " could not be read", t);
        }
        if (!SHIPPED.isEmpty()) {
            AssetStoreWriter.offThread("stat pools", StatPools::applyNow);
        }
    }

    /** Write every tagged stat whose values in force differ from the loaded stat's. The asset writer's thread. */
    private static void applyNow() {
        AssetStore<String, EntityStatType, IndexedLookupTableAssetMap<String, EntityStatType>> store =
                EntityStatType.getAssetStore();
        IndexedLookupTableAssetMap<String, EntityStatType> map = store.getAssetMap();
        List<RawAsset<String>> writes = new ArrayList<>();
        for (Shipped shipped : SHIPPED.values()) {
            StatPoolAsset pool = StatPoolConfig.getInstance().resolve(shipped.id());
            double max = pool != null && pool.max() != null ? pool.max() : shipped.max();
            double regen = pool != null && pool.regenPerSecond() != null ? pool.regenPerSecond() : shipped.regenPerSecond();
            EntityStatType live = map.getAsset(shipped.id());
            if (live != null && same(live.getMax(), max) && same(regenPerSecondOf(live), regen)) {
                continue;
            }
            String json = written(shipped, max, regen).toString();
            writes.add(new RawAsset<>(null, shipped.id(), null, 0, json.toCharArray(), null,
                    ContainedAssetCodec.Mode.NONE));
            SafeLog.info("[" + LOG_TAG + "] " + shipped.id() + ": maximum " + max + ", refill " + regen
                    + " a second (the owner's Stat Pools screen, " + file() + ")");
        }
        if (!writes.isEmpty()) {
            AssetStoreWriter.writeBuffers("stat pools", store, OWNER_PACK, writes);
        }
    }

    /**
     * {@code shipped}'s file with the owner's {@code max} and refill a second in place: its {@code Max}, an
     * {@code InitialValue} that started full kept full (else held under the new maximum), and its first
     * {@code Regenerating} rule's {@code Amount} (its {@code Interval} times the refill), one rule added where the file
     * had none and the owner asked for a refill. Pure.
     */
    @Nonnull
    static JsonObject written(@Nonnull Shipped shipped, double max, double regenPerSecond) {
        JsonObject out = shipped.json().deepCopy();
        out.addProperty("Max", max);
        double initial = same(shipped.initialValue(), shipped.max()) ? max : Math.min(shipped.initialValue(), max);
        out.addProperty("InitialValue", initial);
        JsonArray rules = out.has("Regenerating") && out.get("Regenerating").isJsonArray()
                ? out.getAsJsonArray("Regenerating") : null;
        if (rules != null && rules.size() > 0 && rules.get(0).isJsonObject()) {
            JsonObject first = rules.get(0).getAsJsonObject();
            first.addProperty("Amount", regenPerSecond * shipped.interval());
            if (!first.has("Interval")) {
                first.addProperty("Interval", shipped.interval());
            }
        } else if (regenPerSecond > 0.0) {
            JsonObject rule = new JsonObject();
            rule.addProperty("Interval", DEFAULT_INTERVAL);
            rule.addProperty("Amount", regenPerSecond * DEFAULT_INTERVAL);
            rule.addProperty("RegenType", "Additive");
            JsonArray conditions = new JsonArray();
            JsonObject alive = new JsonObject();
            alive.addProperty("Id", "Alive");
            conditions.add(alive);
            rule.add("Conditions", conditions);
            JsonArray added = new JsonArray();
            added.add(rule);
            out.add("Regenerating", added);
        }
        return out;
    }

    /** The two numbers {@code stat} gives, kept with its shipped file. */
    @Nonnull
    static Shipped shippedOf(@Nonnull String id, @Nonnull JsonObject json, @Nonnull EntityStatType stat) {
        EntityStatType.Regenerating first = firstRule(stat);
        double interval = first != null && first.getInterval() > 0f ? first.getInterval() : DEFAULT_INTERVAL;
        return new Shipped(id, json, stat.getMax(), stat.getInitialValue(), regenPerSecondOf(stat), interval);
    }

    /** What {@code stat}'s first Regenerating rule refills each second; 0 with none or no interval. */
    static double regenPerSecondOf(@Nonnull EntityStatType stat) {
        EntityStatType.Regenerating first = firstRule(stat);
        if (first == null || !(first.getInterval() > 0f)) {
            return 0.0;
        }
        return first.getAmount() / (double) first.getInterval();
    }

    @Nullable
    private static EntityStatType.Regenerating firstRule(@Nonnull EntityStatType stat) {
        EntityStatType.Regenerating[] rules = stat.getRegenerating();
        return rules == null || rules.length == 0 ? null : rules[0];
    }

    /** Does {@code stat}'s file carry {@value #TAG} (any value)? */
    static boolean tagged(@Nonnull EntityStatType stat) {
        try {
            AssetExtraInfo.Data data = EntityStatType.CODEC.getData(stat);
            Map<String, String[]> tags = data == null ? null : data.getRawTags();
            return tags != null && tags.containsKey(TAG);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code id}'s file as its pack ships it: read from the file the store loaded it from, else (a stat with no file,
     * written by code) encoded from the loaded stat; null when neither can be read.
     */
    @Nullable
    private static JsonObject shippedJson(@Nonnull IndexedLookupTableAssetMap<String, EntityStatType> map,
            @Nonnull String id, @Nonnull EntityStatType stat) {
        try {
            Path path = map.getPath(id);
            if (path != null && Files.isRegularFile(path)) {
                JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[" + LOG_TAG + "] the file of the stat '" + id + "' could not be read: " + t.getMessage());
        }
        try {
            BsonValue encoded = EntityStatType.CODEC.encode(stat, new ExtraInfo());
            if (encoded != null && encoded.isDocument()) {
                JsonElement parsed = JsonParser.parseString(encoded.asDocument().toJson());
                return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            }
        } catch (Throwable t) {
            SafeLog.fine("[" + LOG_TAG + "] the stat '" + id + "' could not be encoded: " + t.getMessage());
        }
        return null;
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }
}
