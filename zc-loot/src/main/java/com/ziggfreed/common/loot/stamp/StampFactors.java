package com.ziggfreed.common.loot.stamp;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorRegistry;

/**
 * How much a stamp has put onto an item, as an ordinary factor reading:
 * {@value #ITEM_STAMP_POINTS} reads the stamped points the context ITEM carries
 * ({@link FactorContext#item()}), in total or on one stat.
 *
 * <p><b>Read through the active stamper, never off the item.</b> The item format is the stamper's
 * business ({@link StamperRegistry}), so this reading asks {@link StamperRegistry#inspect} exactly as
 * the budget math does, and a mod that installs a richer stamper is read the same way with no change
 * here. A server with no stamper reads every item as bare ({@code 0}), which is the conservative
 * answer the inspection itself gives.
 *
 * <p>{@code Param} is optional. Absent or blank reads the item's TOTAL stamped points; a stat id
 * reads the points on that stat alone (matched without regard to case, since an author writes the
 * id by hand), {@code 0} when that stat carries none.
 *
 * <p><b>Contributed process-wide</b> through {@link FactorContributions}, so every vocabulary on the
 * server resolves it with nothing registered in the other direction: the stamp belongs to this
 * module, and the portable {@code hytale:} readings in the entity module have no edge to it. The
 * namespace is the library's own, because a stamp is this library's format rather than the
 * engine's. Null when the context carries no item, so a gate on a moment with no item stays shut.
 */
public final class StampFactors {

    /** Who this contribution is attributed to in the ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /**
     * {@code ziggfreedcommon:item_stamp_points} - the stamped points the context item carries, in
     * total, or on the stat named by Param.
     */
    public static final String ITEM_STAMP_POINTS = "ziggfreedcommon:item_stamp_points";

    private StampFactors() {
    }

    /** Claim the id process-wide. One call from the wiring root's {@code setup()}. */
    public static void contribute() {
        FactorContributions.register(ITEM_STAMP_POINTS, OWNER, StampFactors::resolveItemStampPoints);
    }

    /**
     * Register the id into ONE vocabulary, attributed to {@code owner} (a consumer keeping its own
     * registry, or a test). A local registration always outranks the process-wide claim.
     */
    public static void registerInto(@Nonnull FactorRegistry registry, @Nullable String owner) {
        registry.register(ITEM_STAMP_POINTS, owner == null || owner.isBlank() ? OWNER : owner,
                StampFactors::resolveItemStampPoints);
    }

    /** The reading itself; see the class javadoc. */
    @Nullable
    static Double resolveItemStampPoints(@Nonnull FactorContext ctx) {
        if (!ctx.hasItem()) {
            return null;
        }
        ItemStack item = ctx.item();
        StampInspection inspection = StamperRegistry.inspect(item);
        String statId = ctx.param() == null ? null : ctx.param().trim();
        if (statId == null || statId.isEmpty()) {
            return (double) inspection.totalPoints();
        }
        return (double) pointsOn(inspection, statId);
    }

    /** The points on {@code statId}, the exact key first and then without regard to case. */
    private static int pointsOn(@Nonnull StampInspection inspection, @Nonnull String statId) {
        Integer exact = inspection.pointsByStat().get(statId);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Integer> entry : inspection.pointsByStat().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(statId) && entry.getValue() != null) {
                return entry.getValue();
            }
        }
        return 0;
    }
}
