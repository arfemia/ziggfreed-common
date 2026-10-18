package com.ziggfreed.common.track;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
        TrackCategoryAsset category = TrackCategoryConfig.getInstance().category(asset.category());
        PrestigeBuild fromCategory = category == null ? null : category.prestigeBuild();
        return fromCategory == null ? PrestigeBuild.KEEP : fromCategory;
    }

    /** The tier {@code record}'s best level stands in, or null when the curve names none reached. */
    @Nullable
    public CurveTable.Tier tier(@Nonnull TrackRecord record) {
        return table.tierOf(best(record));
    }
}
