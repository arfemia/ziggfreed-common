package com.ziggfreed.common.stats;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.util.SafeLog;

/**
 * The most a capped stat may reach, read off the stat's own file (R1098): a stat the library holds at a cap names it in
 * its native {@code Tags}, one number in the stat's own unit,
 *
 * <pre>{@code
 * "Tags": { "ZigCeiling": [ "200" ] }
 * }</pre>
 *
 * so a server owner changes a cap by shipping the stat's file with another value, and a cap costs no stat type of its
 * own (every living entity holds a slot for each loaded stat type, {@link StatTypeCeiling}). A cap is the server's,
 * the same for every entity: no gear, effect or grant raises it.
 *
 * <p>Read off the loaded stat with its {@code Parent}'s tags merged in as the engine merges them (the parent's values
 * first, the file's own after them, so the last value written is the one read), and kept per stat until the store
 * loads that stat again: the memo holds the loaded asset itself, so a reload is seen on the next read with no listener
 * to wire, and a server running the library alone has it all. A loaded stat that writes no tag, or whose last value is
 * no number, reads the caller's fallback (the number the library ships), named once per load; a stat the server does
 * not have, or a store that cannot be read (a unit-test JVM), reads the fallback quietly.
 */
public final class CeilingTags {

    /** The stat tag naming the stat's cap. */
    public static final String TAG = "ZigCeiling";

    /** One stat's cap as read off one loaded asset. */
    private record Read(@Nonnull EntityStatType asset, double value) {
    }

    /** By stat id as the caller names it. */
    private static final Map<String, Read> READS = new ConcurrentHashMap<>();

    private CeilingTags() {
    }

    /**
     * The cap {@code statId}'s file names in its {@value #TAG} tag, else {@code fallback}. Any thread; never throws.
     */
    public static double ceilingOf(@Nullable String statId, double fallback) {
        if (statId == null || statId.isBlank()) {
            return fallback;
        }
        int index = StatIndexCache.resolve(statId);
        if (index == StatIndexCache.UNRESOLVED) {
            return fallback;
        }
        EntityStatType type;
        try {
            type = EntityStatType.getAssetMap().getAsset(index);
        } catch (Throwable t) {
            return fallback;
        }
        if (type == null) {
            return fallback;
        }
        Read read = READS.get(statId);
        if (read != null && read.asset() == type) {
            return read.value();
        }
        double value = readOff(statId, type, fallback);
        READS.put(statId, new Read(type, value));
        return value;
    }

    /** {@code type}'s cap, or {@code fallback} named once for this load of it. */
    private static double readOff(@Nonnull String statId, @Nonnull EntityStatType type, double fallback) {
        Double value;
        try {
            AssetExtraInfo.Data data = EntityStatType.CODEC.getData(type);
            value = read(data == null ? null : data.getRawTags());
        } catch (Throwable t) {
            value = null;
        }
        if (value == null) {
            SafeLog.warn("[stats] the stat '" + statId + "' writes no " + TAG + " tag with a number in its file, so its"
                    + " cap reads the library's " + fallback);
            return fallback;
        }
        return value;
    }

    /**
     * The cap {@code rawTags} names under {@value #TAG}: its last value that is not blank, read as a number; null when
     * none is written, or that value is no finite number. Pure.
     */
    @Nullable
    static Double read(@Nullable Map<String, String[]> rawTags) {
        String[] values = rawTags == null ? null : rawTags.get(TAG);
        if (values == null) {
            return null;
        }
        for (int i = values.length - 1; i >= 0; i--) {
            String value = values[i];
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                double number = Double.parseDouble(value.trim());
                return Double.isFinite(number) ? number : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
