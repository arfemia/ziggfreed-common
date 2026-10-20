package com.ziggfreed.common.track.gain;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.track.GainCall;

/**
 * The {@value GainFactors#BONUS} factor (R734): the player's gain stats ({@link TrackGainStats}) for the track,
 * summed, held at their ceiling and paid as {@code 1 + percent / 100}. It sits in the track's {@code Gain.Additive}
 * group unless the track says otherwise, so it adds beside the boosts ({@code bonus + (boost - 1)}) rather than
 * multiplying them.
 *
 * <p>The stats it reads are resolved when the plan is built (the plan is built again on every epoch move, a stat
 * store load among them); their totals are read at each gain, since gear, an effect or a grant can move them at any
 * time with no epoch of their own. A gain costs one stat-map lookup and a read per resolved stat. With no player
 * entity (another kind of subject) it compiles to nothing.
 */
public final class BonusGainFactor implements GainFactor {

    public static final BonusGainFactor INSTANCE = new BonusGainFactor();

    private BonusGainFactor() {
    }

    @Nonnull
    @Override
    public GainStep compile(@Nonnull GainScope scope) {
        if (scope.ref() == null) {
            // No player entity to read (another kind of subject, or a player not in a world yet, whose plans the
            // ready moves): nothing to say.
            return GainStep.NONE;
        }
        List<String> ids = TrackGainStats.statIdsFor(scope);
        List<Integer> resolved = new ArrayList<>(ids.size());
        for (String id : ids) {
            int index = StatIndexCache.resolve(id);
            if (index != StatIndexCache.UNRESOLVED && !resolved.contains(index)) {
                resolved.add(index);
            }
        }
        if (resolved.isEmpty()) {
            return GainStep.NONE;
        }
        int[] indices = new int[resolved.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = resolved.get(i);
        }
        int ceiling = StatIndexCache.resolve(TrackGainStats.CEILING_STAT);
        return (call, amount) -> amount * multiplierFor(call, indices, ceiling);
    }

    /** The multiplier the player's stats give right now: 1 when nothing can be read. */
    private static double multiplierFor(@Nonnull GainCall call, @Nonnull int[] indices, int ceiling) {
        EntityStatMap stats = statsOf(call.ref());
        if (stats == null) {
            return 1.0;
        }
        double points = 0.0;
        for (int index : indices) {
            points += maxOf(stats, index, 0.0);
        }
        if (points == 0.0) {
            return 1.0;
        }
        double top = ceiling == StatIndexCache.UNRESOLVED ? TrackGainStats.DEFAULT_CEILING
                : maxOf(stats, ceiling, TrackGainStats.DEFAULT_CEILING);
        return TrackGainStats.multiplier(points, top);
    }

    private static double maxOf(@Nonnull EntityStatMap stats, int index, double fallback) {
        EntityStatValue value = stats.get(index);
        return value == null ? fallback : value.getMax();
    }

    @Nullable
    private static EntityStatMap statsOf(@Nullable Ref<EntityStore> ref) {
        try {
            return ref == null || !ref.isValid() ? null : ref.getStore().getComponent(ref, EntityStatMap.getComponentType());
        } catch (Throwable t) {
            return null;
        }
    }
}
