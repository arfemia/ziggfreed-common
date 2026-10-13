package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * The library's own leaderboard words, each a key in {@code ziggfreedcommon.leaderboard.lang} resolved
 * on the reader's client: the column, sort and view words every records screen shares, the viewer's
 * strip and the tooltip defaults on {@link LeaderboardScreenMessages}, the Records tab, and
 * {@code /zigleaderboard}'s replies (under {@link #PREFIX} plus {@code command.}).
 */
public final class EncounterLeaderboardMessages {

    /** The key family every line resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.leaderboard.";

    private EncounterLeaderboardMessages() {
    }

    /** A line of this family as a client-resolved message, for the screen or a command reply. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }
}
