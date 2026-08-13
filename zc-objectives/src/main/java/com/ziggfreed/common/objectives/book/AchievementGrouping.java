package com.ziggfreed.common.objectives.book;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.i18n.Msg;

/**
 * How the achievement tab groups its rows: which bucket a piece of content belongs to, where that
 * bucket reads among the others, what its header is called, and when a header is drawn at all.
 *
 * <p>Every decision here is a pure one, taking the folded taxonomy as an argument rather than
 * reaching for it, which is what keeps the rules readable and testable away from a page.
 */
final class AchievementGrouping {

    /**
     * The bucket for content that names no category, and for content another mod folded (whose
     * category this library cannot see). One bucket, not one per reason: a player reading the list
     * has no use for the difference, and two buckets holding "everything else" read as a bug.
     */
    static final String UNCATEGORISED = "";

    /**
     * The key a category is labelled with when its own file names none. Deliberately unprefixed:
     * a category is a word any mod's content can file itself under, so the convention cannot live
     * inside one namespace.
     */
    static final String CONVENTION_KEY_PREFIX = "achievement.category.";

    private AchievementGrouping() {
    }

    /** The bucket an achievement's category names, or {@link #UNCATEGORISED}. */
    @Nonnull
    static String bucketOf(@Nullable String category) {
        return category == null || category.isBlank() ? UNCATEGORISED : category;
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

    /**
     * What a NAMED bucket's header says, in the player's own language wherever that is possible.
     *
     * <p>Three answers in order. An authored {@code TitleKey} wins, because somebody said in so many
     * words what this group is called. Failing that, a category a file describes is labelled by the
     * convention key, which is the route the schema points an author at. Failing both - nothing
     * describes this category at all - the word content filed itself under is drawn as it stands,
     * tidied up: an untranslated word a player can read beats a raw key they cannot.
     *
     * <p>The uncategorised bucket never reaches here; it has a line of its own on the page.
     */
    @Nonnull
    static Message label(@Nonnull String bucket, @Nullable AchievementCategoryAsset presentation) {
        if (presentation == null) {
            return Msg.raw(humanize(bucket));
        }
        String titleKey = presentation.getTitleKey();
        return Msg.key(titleKey != null ? titleKey : CONVENTION_KEY_PREFIX + bucket);
    }

    /** {@code boss_fights} read as {@code Boss Fights}: separators become spaces, words open big. */
    @Nonnull
    static String humanize(@Nonnull String category) {
        StringBuilder out = new StringBuilder(category.length());
        boolean opening = true;
        for (int i = 0; i < category.length(); i++) {
            char c = category.charAt(i);
            if (c == '_' || c == '-' || c == '.') {
                out.append(' ');
                opening = true;
            } else {
                out.append(opening ? Character.toUpperCase(c) : c);
                opening = false;
            }
        }
        return out.toString().trim();
    }

    /**
     * Tracks which headers a painter has already drawn while it walks a SORTED list, so a header is
     * only ever drawn for a group that turned out to have a row in it. Nothing here counts or
     * measures anything: it answers "is this row the first of a new group?" and the painter decides
     * what to do about it.
     *
     * <p>A category is forgotten whenever the section changes, because the same category appears
     * again under the next lifecycle heading and needs its own header there.
     *
     * @param <S> the section type the caller groups by, usually its own enum
     */
    static final class HeaderWalk<S> {

        @Nullable
        private S section;

        @Nullable
        private String category;

        /** True when this row opens a section nothing has been drawn for yet. */
        boolean enterSection(@Nonnull S next) {
            if (next.equals(section)) {
                return false;
            }
            section = next;
            category = null;
            return true;
        }

        /** True when this row opens a category nothing has been drawn for in this section. */
        boolean enterCategory(@Nonnull String next) {
            if (next.equals(category)) {
                return false;
            }
            category = next;
            return true;
        }
    }
}
