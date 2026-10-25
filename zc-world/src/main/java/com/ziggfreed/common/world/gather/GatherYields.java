package com.ziggfreed.common.world.gather;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The yield a player gathers with: {@code GatherYield} plus each stat a mod counts for the block or the harvest
 * ({@link GatherStatSources}, the {@link GatherStatSources#YIELD} line), in whole percent points, added to the chance of
 * a bonus find on a block break or a hand harvest. The library's bonus rows add it to a row's own {@code Chance}, and a
 * {@code ZigLootable} inside a broken block's drop list adds it to each roll's {@code Chance}, so it raises a find's
 * odds and never rolls a second time; a chance nobody wrote (a certain find) takes nothing from it. A holder condition
 * reaches it as a {@code GatherYield_While<Condition>} stat folded onto {@code GatherYield}. World-thread only.
 */
public final class GatherYields {

    /** The base yield stat every break and harvest reads. */
    public static final String YIELD_STAT = "GatherYield";

    private GatherYields() {
    }

    /**
     * The yield {@code player} gathers {@code id} with at {@code on}, in whole percent points: their
     * {@code GatherYield} and every source's stat for it. 0 for no player, no stat map or no id.
     */
    public static double percentFor(@Nullable ComponentAccessor<EntityStore> store, @Nullable Ref<EntityStore> player,
            @Nonnull GatherStatSources.On on, @Nullable String id) {
        if (store == null || player == null) {
            return 0.0;
        }
        EntityStatMap stats = GatherReads.stats(store, player);
        if (stats == null) {
            return 0.0;
        }
        double sum = GatherReads.max(stats, YIELD_STAT);
        if (id != null && !id.isBlank()) {
            for (String stat : GatherStatSources.statsFor(GatherStatSources.YIELD, on, id)) {
                sum += GatherReads.max(stats, stat);
            }
        }
        return Double.isFinite(sum) ? sum : 0.0;
    }
}
