package com.ziggfreed.common.objectives.book.achievement;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;

/**
 * How the achievement tab groups its rows: which bucket a piece of content belongs to and where that bucket
 * reads among the others. What a bucket is CALLED is {@code CategoryNames}' answer, the one every listing
 * surface asks.
 *
 * <p>Every decision here is a pure one, taking the folded taxonomy as an argument rather than reaching for it,
 * which is what keeps the rules readable and testable away from a page.
 */
final class AchievementGrouping {

    /**
     * The bucket for content that names no category, and for content another mod folded (whose
     * category this library cannot see). One bucket, not one per reason: a player reading the list
     * has no use for the difference, and two buckets holding "everything else" read as a bug.
     */
    static final String UNCATEGORISED = "";

    private AchievementGrouping() {
    }

    /** The bucket an achievement's category names, or {@link #UNCATEGORISED}. */
    @Nonnull
    static String bucketOf(@Nullable String category) {
        return category == null || category.isBlank() ? UNCATEGORISED : category;
    }

    /**
     * The bucket an achievement files under: the runtime object's own listing first, the folded
     * definition's as the fallback for content whose fold predates the model carrying it; lower case.
     */
    @Nonnull
    static String bucketOf(@Nonnull Achievement achievement) {
        String category = achievement.category();
        if (category == null || category.isBlank()) {
            category = ProgressionDefaults.achievementCategory(achievement.id());
        }
        return bucketOf(category == null ? null : category.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Where a bucket reads among the others, given the rank the folded taxonomy has for it.
     *
     * <p>Three tiers, and the bottom two are the reason this is not just the taxonomy's own answer:
     * a category a file describes reads where that file says; a category nothing describes reads
     * after every described one; and the uncategorised bucket reads after everything, so the list
     * ends with "everything else" rather than opening with it.
     */
    static int rankOf(@Nullable String category, int describedRank) {
        if (category == null || category.isBlank()) {
            return Integer.MAX_VALUE;
        }
        return Math.min(describedRank, Integer.MAX_VALUE - 1);
    }
}
