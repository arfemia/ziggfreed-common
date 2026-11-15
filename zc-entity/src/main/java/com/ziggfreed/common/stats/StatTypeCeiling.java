package com.ziggfreed.common.stats;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The ceiling on how many entity stat types a server loads (R759): every living entity holds a slot for every stat
 * type, and the engine loops all of them on every entity every tick (R758), so the family keeps its count bounded.
 * zc's boot audit names the count once a boot (INFO) and warns past {@value #CEILING}; the count is the asset map's
 * next index, since a removed stat keeps its slot. The family's build holds the shipped count under it too (the MMO
 * build's {@code StatTypeCeilingTest}, which counts every family repo's and pack's stat files and the per-skill
 * families, and fails past it).
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

    private StatTypeCeiling() {
    }

    /** The boot audit's pass: the loaded count, and a warning past the ceiling. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            return audit(EntityStatType.getAssetMap().getNextIndex());
        } catch (Throwable t) {
            SafeLog.warn("[stats] the stat type count could not be read: " + t.getMessage(), t);
            return List.of();
        }
    }

    /** The findings for {@code count} loaded stat types. Pure. */
    @Nonnull
    public static List<Finding> audit(int count) {
        Finding line = Finding.info(DOMAIN, COUNT, count + " entity stat types loaded (ceiling " + CEILING + ")",
                "EntityStatType");
        if (count <= CEILING) {
            return List.of(line);
        }
        return List.of(line, Finding.warning(DOMAIN, PAST_CEILING, count + " entity stat types are loaded, past the"
                + " family's ceiling of " + CEILING + ": every living entity holds and ticks a slot for each, so"
                + " merge a stat family before adding more", "EntityStatType"));
    }
}
