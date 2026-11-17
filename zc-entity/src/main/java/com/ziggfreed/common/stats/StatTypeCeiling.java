package com.ziggfreed.common.stats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The ceiling on how many entity stat types a server loads (R759): every living entity holds a slot for every stat
 * type, and the engine loops all of them on every entity every tick (R758), so the family keeps its count bounded.
 * zc's boot audit names the count once a boot (INFO), with how many each asset pack owns, and warns past
 * {@value #CEILING}; the count is the asset map's next index, since a removed stat keeps its slot. The family's build
 * holds the shipped count under it too (the MMO build's {@code StatTypeCeilingTest}, which counts every family repo's
 * and pack's stat files and the per-skill families, and fails past it).
 */
public final class StatTypeCeiling {

    /** The most stat types the family loads before the audit warns (R759, the lead's call). */
    public static final int CEILING = 750;

    /** The audit's domain. */
    public static final String DOMAIN = "stat_types";

    /** The label the boot audit's pass prints its lines under. */
    public static final String LOG_LABEL = "[stats] stat type count";

    public static final String COUNT = "STAT_TYPE_COUNT";
    public static final String PAST_CEILING = "STAT_TYPES_PAST_CEILING";

    /** The label the game's own asset pack prints under in the per-pack count. */
    static final String BASE_PACK_LABEL = "Hytale";

    private StatTypeCeiling() {
    }

    /** The boot audit's pass: the loaded count with each owning pack's share, and a warning past the ceiling. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            IndexedLookupTableAssetMap<String, EntityStatType> map = EntityStatType.getAssetMap();
            return audit(map.getNextIndex(), byPack(map));
        } catch (Throwable t) {
            SafeLog.warn("[stats] the stat type count could not be read: " + t.getMessage(), t);
            return List.of();
        }
    }

    /** The findings for {@code count} loaded stat types. Pure. */
    @Nonnull
    public static List<Finding> audit(int count) {
        return audit(count, Map.of());
    }

    /**
     * The findings for {@code count} loaded stat types, the INFO line naming how many each pack in {@code byPack}
     * owns (in the map's order; none named when it is empty). Pure.
     */
    @Nonnull
    public static List<Finding> audit(int count, @Nonnull Map<String, Integer> byPack) {
        Finding line = Finding.info(DOMAIN, COUNT, count + " entity stat types loaded (ceiling " + CEILING + ")"
                + packShares(byPack), "EntityStatType");
        if (count <= CEILING) {
            return List.of(line);
        }
        return List.of(line, Finding.warning(DOMAIN, PAST_CEILING, count + " entity stat types are loaded, past the"
                + " family's ceiling of " + CEILING + ": every living entity holds and ticks a slot for each, so"
                + " merge a stat family before adding more", "EntityStatType"));
    }

    /** {@code "; by pack: A 12, B 156"} for {@code byPack}, empty for none. Pure. */
    @Nonnull
    static String packShares(@Nonnull Map<String, Integer> byPack) {
        if (byPack.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("; by pack: ");
        String separator = "";
        for (Map.Entry<String, Integer> entry : byPack.entrySet()) {
            out.append(separator).append(entry.getKey()).append(' ').append(entry.getValue());
            separator = ", ";
        }
        return out.toString();
    }

    /**
     * How many loaded stat types each asset pack owns, by the pack the engine records for the stat's id (the last
     * pack to write it), the game's own pack named {@value #BASE_PACK_LABEL}, the rest by their pack keys in
     * alphabetical order after it; a stat with no recorded pack counts under {@code ?}.
     */
    @Nonnull
    private static Map<String, Integer> byPack(@Nonnull IndexedLookupTableAssetMap<String, EntityStatType> map) {
        String basePack = basePackName();
        Map<String, Integer> others = new TreeMap<>();
        int base = 0;
        for (String id : map.getAssetMap().keySet()) {
            String pack = map.getAssetPack(id);
            if (pack != null && pack.equals(basePack)) {
                base++;
            } else {
                others.merge(pack == null ? "?" : pack, 1, Integer::sum);
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        if (base > 0) {
            out.put(BASE_PACK_LABEL, base);
        }
        out.putAll(others);
        return out;
    }

    /** The game's own asset pack's key, null when it cannot be read. */
    @Nullable
    private static String basePackName() {
        try {
            return AssetModule.get().getBaseAssetPack().getName();
        } catch (Throwable t) {
            return null;
        }
    }
}
