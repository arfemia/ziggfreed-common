package com.ziggfreed.common.stats.gearset;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SlotPlan;

/**
 * Which tile each worn set shows on the buff bar, decided without the engine: one tile per set
 * while any of its tiers is active. The set's own {@code Badge} when one of its active tiers names
 * one (the highest such tier by position, so the tile follows the ladder), else a slot of the
 * library's default pool, seated by {@link SlotPlan} so a set keeps its slot from recompute to
 * recompute and a fourth set naming no badge shares the third slot. The engine shows one tile per
 * effect id, which is why the default is a pool and not one id.
 *
 * <p>The pool ids belong to the gear-set engine: it puts them on and takes them off itself, so
 * nothing else applies them, and a set never names one as its {@code Badge} (the validator says so).
 * A pack restyles the pool by shipping its own {@code Zig_Gear_Set} (the other two name it as their
 * {@code Parent}). Pure.
 */
final class GearSetBadges {

    /** The default tile, the first slot of the pool; {@code _2} and {@code _3} name it as their Parent. */
    static final String DEFAULT_TILE = "Zig_Gear_Set";

    /** How many sets naming no badge show a tile of their own; any further one shares the last. */
    static final int POOL_SIZE = 3;

    /** The default pool's ids, smallest first: {@code Zig_Gear_Set}, {@code Zig_Gear_Set_2}, {@code Zig_Gear_Set_3}. */
    static final List<String> DEFAULT_TILES = SlotPlan.slotIds(DEFAULT_TILE, POOL_SIZE);

    private GearSetBadges() {
    }

    /**
     * The badge a set shows: that of the highest tier in {@code active} (positions into {@code tiers})
     * that names one; null when none does, so the set takes a default slot.
     */
    @Nullable
    static String badgeOf(@Nonnull List<GearSetAsset.Tier> tiers, @Nonnull Collection<Integer> active) {
        int best = -1;
        String badge = null;
        for (Integer index : active) {
            if (index == null || index <= best || index >= tiers.size()) {
                continue;
            }
            String id = tiers.get(index).badgeId();
            if (id != null) {
                best = index;
                badge = id;
            }
        }
        return badge;
    }

    /**
     * Seat every set that shows the default tile.
     *
     * @param pooled   the folded ids of the worn sets whose active tiers name no badge, in the order
     *                 that settles a tie (set-id order, the order the recompute walks)
     * @param previous each set's slot from the last recompute in this store
     * @return each set's slot id, in {@code pooled}'s order
     */
    @Nonnull
    static Map<String, String> seat(@Nonnull List<String> pooled, @Nonnull Map<String, String> previous) {
        return SlotPlan.assign(pooled, previous, DEFAULT_TILES);
    }

    /** True when {@code effectId} is one of the default pool's ids, matched without regard to case. */
    static boolean isDefaultTile(@Nullable String effectId) {
        if (effectId == null) {
            return false;
        }
        for (String tile : DEFAULT_TILES) {
            if (tile.equalsIgnoreCase(effectId.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code ids}, then the default pool's ids, distinct and in that order: the looks a hydrate answers
     * for before the player's saved record adds its own, since a default tile is saved with the player
     * like any look and no folded set names it.
     */
    @Nonnull
    static Set<String> withDefaultTiles(@Nonnull Collection<String> ids) {
        Set<String> out = new LinkedHashSet<>(ids);
        out.addAll(DEFAULT_TILES);
        return out;
    }
}
