package com.ziggfreed.common.track;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.track.asset.PrestigeBuild;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackCategoryAsset;
import com.ziggfreed.common.track.asset.TrackCategoryConfig;
import com.ziggfreed.common.track.curve.CurveTable;

/**
 * A usable track: switched on, with its curve tabulated. What every reading and write resolves first
 * ({@link TrackService#def}); a track that is off, gated out or whose curve is not loaded has none.
 *
 * @param asset the track as folded
 * @param table its curve's table
 */
public record TrackDef(@Nonnull TrackAsset asset, @Nonnull CurveTable table) {

    /** The track id, lower-cased as every key holds it. */
    @Nonnull
    public String id() {
        return TrackKey.normalize(asset.getId());
    }

    /** The current climb's level for {@code record}. */
    public int level(@Nonnull TrackRecord record) {
        return table.levelOf(record.value());
    }

    /** The best level {@code record} ever reached: what it has on record, or its current level if higher. */
    public int best(@Nonnull TrackRecord record) {
        return Math.max(record.best(), level(record));
    }

    /** The most a climb holds on this track: its cap level's start, or the curve's own top. */
    public long ceiling() {
        return table.ceiling(asset.cap());
    }

    /** Where a fresh climb (and a prestiged one) starts: 0, held inside the curve's range. */
    public long start() {
        return Math.min(Math.max(0L, table.minimum()), ceiling());
    }

    /**
     * The build a prestige of this track keeps (Keep, Remember or Clear, R641): the track's own
     * {@code Prestige.Build}, else its category's, else {@link PrestigeBuild#KEEP}.
     */
    @Nonnull
    public PrestigeBuild prestigeBuild() {
        PrestigeBuild own = asset.ownPrestigeBuild();
        if (own != null) {
            return own;
        }
        TrackCategoryAsset category = category();
        PrestigeBuild fromCategory = category == null ? null : category.prestigeBuild();
        return fromCategory == null ? PrestigeBuild.KEEP : fromCategory;
    }

    /**
     * How many saved builds a player keeps on this track: its own {@code Loadouts.Max}, else its category's, else
     * 0 (none).
     */
    public int loadoutsMax() {
        Integer own = asset.loadouts() == null ? null : asset.loadouts().max();
        if (own != null) {
            return own;
        }
        TrackCategoryAsset category = category();
        Integer fromCategory = category == null || category.loadouts() == null ? null : category.loadouts().max();
        return fromCategory == null ? 0 : fromCategory;
    }

    /** What a swap to a saved build costs: the track's own {@code Loadouts.SwapCost}, else its category's, else free. */
    @Nonnull
    public CostAsset swapCost() {
        CostAsset own = asset.loadouts() == null ? null : asset.loadouts().swapCost();
        if (own != null) {
            return own;
        }
        TrackCategoryAsset category = category();
        CostAsset fromCategory = category == null || category.loadouts() == null ? null
                : category.loadouts().swapCost();
        return fromCategory == null ? CostAsset.FREE : fromCategory;
    }

    /** What a respec costs: the track's own {@code Respec.Cost}, else its category's, else free. */
    @Nonnull
    public CostAsset respecCost() {
        CostAsset own = asset.respec() == null ? null : asset.respec().cost();
        if (own != null) {
            return own;
        }
        TrackCategoryAsset category = category();
        CostAsset fromCategory = category == null || category.respec() == null ? null : category.respec().cost();
        return fromCategory == null ? CostAsset.FREE : fromCategory;
    }

    /**
     * How many times a respec pays {@link #respecCost()} at the best level {@code bestLevel}: the track's own
     * {@code Respec.PerLevel}, else its category's, times that level, rounded up and at least once (R665); once
     * whatever the level when neither writes one, or one writes 0 or less.
     */
    public int respecTimes(int bestLevel) {
        Double own = asset.respec() == null ? null : asset.respec().perLevel();
        TrackCategoryAsset category = own != null ? null : category();
        Double perLevel = own != null ? own
                : category == null || category.respec() == null ? null : category.respec().perLevel();
        return timesAt(perLevel, bestLevel);
    }

    /**
     * The pure rule behind {@link #respecTimes}: {@code perLevel} times {@code bestLevel}, rounded up, at least 1;
     * 1 for an unwritten, zero or negative {@code perLevel}.
     */
    public static int timesAt(@Nullable Double perLevel, int bestLevel) {
        if (perLevel == null || !(perLevel > 0) || bestLevel <= 0) {
            return 1;
        }
        double times = Math.ceil(perLevel * bestLevel);
        return times >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) times);
    }

    @Nullable
    private TrackCategoryAsset category() {
        return TrackCategoryConfig.getInstance().category(asset.category());
    }

    /** The tier {@code record}'s best level stands in, or null when the curve names none reached. */
    @Nullable
    public CurveTable.Tier tier(@Nonnull TrackRecord record) {
        return table.tierOf(best(record));
    }
}
