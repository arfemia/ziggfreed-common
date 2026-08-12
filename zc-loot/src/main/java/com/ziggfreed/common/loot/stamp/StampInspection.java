package com.ziggfreed.common.loot.stamp;

import java.util.Map;

import javax.annotation.Nonnull;

/**
 * What an item ALREADY carries, as the budget math needs to see it: how many points in total, how
 * many per stat, and how many times it has been stamped before.
 *
 * <p>All three are read through a {@link Stamper} rather than off the item directly, because the
 * item format is the stamper's business. Points are whole numbers here on purpose - budgets and
 * ceilings are counted in whole points, so a stamper storing fractions rounds when it reports.
 */
public record StampInspection(int totalPoints, @Nonnull Map<String, Integer> pointsByStat, int stampCount) {

    public StampInspection {
        pointsByStat = Map.copyOf(pointsByStat);
    }

    /** A bare item: nothing stamped, no prior points, no history. */
    @Nonnull
    public static StampInspection empty() {
        return new StampInspection(0, Map.of(), 0);
    }

    /** How many points {@code statId} already carries, or 0 when it carries none. */
    public int pointsOf(@Nonnull String statId) {
        Integer points = pointsByStat.get(statId);
        return points == null ? 0 : points;
    }
}
