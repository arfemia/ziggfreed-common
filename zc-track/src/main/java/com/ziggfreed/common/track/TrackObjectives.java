package com.ziggfreed.common.track;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.track.curve.CurveTable;

/**
 * The two objective kinds a climb feeds, and how their targets read. The kinds' files
 * ({@code ObjectiveKinds/Track_Level.json}, {@code Track_Tier.json}) and their sentences ship with this module;
 * zc-objectives' {@code ZigTrackProducer} fires them, never this module or a consumer. Both are value-based (a
 * step keeps the highest it has seen), and both are restated whenever the best level rises and at every ready,
 * so a restatement changes nothing and a prestige never undoes a step.
 * <ul>
 *   <li>{@value #LEVEL}: Target the track id, Amount the best level ever reached. A step naming a track reads
 *       that track's best level; one naming none reads the best of any track.</li>
 *   <li>{@value #TIER}: Qualifier the track id, and two shapes of moment. One per tier the best level has
 *       reached on the track's curve, Target the tier id, Amount {@value #TIER_REACHED}, so a step naming a tier
 *       reads 1 once it is reached. And one counting them, Target {@code ""} ({@link #TIER_COUNT_TARGET}, which
 *       no step naming a tier matches), Amount how many of the curve's tiers the best level has reached, so a
 *       step naming no tier reads that count.</li>
 * </ul>
 */
public final class TrackObjectives {

    /** Reaching a level on a track. */
    public static final String LEVEL = "TRACK_LEVEL";

    /** Reaching tiers on a track. */
    public static final String TIER = "TRACK_TIER";

    /** A {@value #TIER} moment's Amount for one tier reached. */
    public static final long TIER_REACHED = 1L;

    /**
     * The Target of the {@value #TIER} moment that counts a track's tiers reached: blank, so only a step that
     * names no tier (the match-all shorthand) reads it, and a step naming a tier never does.
     */
    public static final String TIER_COUNT_TARGET = "";

    private TrackObjectives() {
    }

    /** A {@value #LEVEL} step's target named: the track's own name, or null for a track no file has. */
    @Nullable
    public static Message levelTargetName(@Nonnull String trackId) {
        return TrackText.nameOf(trackId);
    }

    /**
     * A {@value #TIER} step's target named: the first usable track's curve naming that tier, by its {@code Name};
     * null when no curve names it.
     */
    @Nullable
    public static Message tierTargetName(@Nonnull String tierId) {
        for (TrackDef def : TrackService.defs()) {
            CurveTable.Tier tier = def.table().tier(tierId);
            if (tier != null) {
                return TrackText.tierName(tier);
            }
        }
        return null;
    }
}
