package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;

/**
 * Every line the kit says itself, from the library's {@code ziggfreedcommon.ui.lang} under {@code kit.}: the
 * "Show N more" row, the no-match empty state and its button, and the progress block's count and percent. Numbers
 * bind as typed params, so the viewer's own client groups them.
 */
public final class KitText {

    public static final String PREFIX = "ziggfreedcommon.ui.kit.";

    private KitText() {
    }

    /** The full registered key of {@code key}. */
    @Nonnull
    public static String key(@Nonnull String key) {
        return PREFIX + key;
    }

    /** "Show {0} more". */
    @Nonnull
    public static Message showMore(long more) {
        return Msg.key(PREFIX + "show_more", more);
    }

    /** "Nothing matches": the empty state's title when filters hide every row. */
    @Nonnull
    public static Message nothingMatches() {
        return Msg.key(PREFIX + "nothing_matches");
    }

    /** The no-match empty state's line. */
    @Nonnull
    public static Message nothingMatchesLine() {
        return Msg.key(PREFIX + "nothing_matches_line");
    }

    /** "Clear filters": the no-match empty state's button. */
    @Nonnull
    public static Message clearFilters() {
        return Msg.key(PREFIX + "clear_filters");
    }

    /** "{0} / {1}": a count toward a total. */
    @Nonnull
    public static Message count(long current, long total) {
        return Msg.key(PREFIX + "count", current, total);
    }

    /** "{0}%". */
    @Nonnull
    public static Message percent(long percent) {
        return Msg.key(PREFIX + "percent", percent);
    }
}
