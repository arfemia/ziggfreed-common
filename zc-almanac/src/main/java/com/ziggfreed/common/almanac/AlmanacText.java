package com.ziggfreed.common.almanac;

import java.awt.Color;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every line the Almanac says, on its page and at the command line, and the rule it says them by: a
 * sentence is a KEY the reader's own client resolves, a season or stat name a pack authored is that
 * pack's key resolved to its registered id through {@link ContentKeys}, and a count is a typed number.
 *
 * <p><b>Keys live in {@code ziggfreedcommon.almanac.lang}</b>, so the in-file key drops the
 * {@value #PREFIX} the file name carries. A command description is a key too: the engine resolves it
 * through its own localization module.
 */
public final class AlmanacText {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.almanac.";

    /** Every key this module speaks, for the test that holds the English file to it. */
    public static final List<String> SPOKEN = List.of(
            "title", "seasons.empty", "status.live", "status.between",
            "section.season", "section.last", "section.lifetime", "section.keepsakes",
            "section.achievements", "attended.yes", "attended.count", "stat.line", "stat.none",
            "keepsake.line", "keepsake.none", "achievements.count", "banner.line", "banner.earned",
            "desc.family", "desc.open", "desc.arg.event",
            "open.needsPlayer", "open.failed", "open.off",
            "achievement.category.seasons",
            // The redesigned page (W1-58): its lead, the season's countdown and dates, the scope, the
            // tiles, the keepsake shelf, the record card, the empty state, the year at a glance, a
            // consumer's headline and the book's Seasons statistics.
            "lead", "chip.live", "chip.last_day", "chip.returns_in", "chip.returns_on", "chip.starts_in",
            "chip.between",
            "month.1", "month.2", "month.3", "month.4", "month.5", "month.6",
            "month.7", "month.8", "month.9", "month.10", "month.11", "month.12",
            "month.short.1", "month.short.2", "month.short.3", "month.short.4", "month.short.5", "month.short.6",
            "month.short.7", "month.short.8", "month.short.9", "month.short.10", "month.short.11", "month.short.12",
            "window", "scope.year", "scope.every", "scope.took_part", "scope.not_yet", "scope.taken_part_count",
            "tile.in_all", "tile.server", "keepsakes.title", "keepsakes.meta", "keepsake.to_earn",
            "keepsake.missed", "achievements.title", "record.title", "record.seasons", "record.keepsakes",
            "empty.none.title", "empty.none.line", "hint.first_time", "glance.title", "headline.live",
            "stats.section", "stats.row.meta", "stats.open");

    private static final Color BAD = new Color(0xFF5555);

    private AlmanacText() {
    }

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    /** A line of this family as a client-resolved message. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    /**
     * A pack-authored key resolved to the id its pack registered, or {@code fallback} as raw text when
     * nothing is authored, so an unnamed season still reads as its id rather than as a blank.
     */
    @Nonnull
    public static Message authored(@Nullable String authoredKey, @Nonnull String fallback) {
        return authoredKey == null || authoredKey.isBlank() ? Msg.raw(fallback) : ContentKeys.tr(authoredKey.trim());
    }

    /** Something was refused at the command line, and this is why. */
    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(line(key, args).color(BAD));
    }
}
