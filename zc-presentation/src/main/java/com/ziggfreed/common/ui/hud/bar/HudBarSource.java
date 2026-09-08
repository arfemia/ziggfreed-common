package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Where a bar's numbers come from: the mod that owns a value answers, for one player and one of its
 * own value ids, how far along the value is and where it tops out. The panel knows nothing else
 * about the value - not what it measures, not what unit it is in, not why it moved.
 *
 * <p>A consumer registers ONE of these under its namespace ({@link HudBarSources#register}) and
 * ships a {@link HudBarAsset} per value it wants a bar for, each naming a {@code namespace:local}
 * source. It is asked on the player's world thread, at paint time, only for a bar that is live, and
 * only with the LOCAL half of the id (the part after the colon), so a source parses its own
 * vocabulary and nothing else.
 *
 * <p>Answer null for a value this source cannot read right now (a player with no data yet, an id it
 * does not recognise, a player who has this display switched off): the bar is simply not drawn.
 */
@FunctionalInterface
public interface HudBarSource {

    /**
     * The current reading of {@code localId} for {@code playerRef}, or null when there is nothing to
     * draw.
     */
    @Nullable
    Reading read(@Nonnull PlayerRef playerRef, @Nonnull String localId);

    /**
     * Where a value stands: {@code current} out of {@code maximum}. A maximum of zero or less reads
     * as a full bar, so a value with no ceiling left (a final stage) draws as complete rather than
     * dividing by nothing.
     */
    record Reading(double current, double maximum) {

        /** A bar drawn full. */
        public static final Reading FULL = new Reading(1, 1);

        /** The fill fraction, held to {@code 0..1}. */
        public double fraction() {
            if (maximum <= 0) {
                return 1.0;
            }
            return Math.max(0.0, Math.min(1.0, current / maximum));
        }
    }
}
