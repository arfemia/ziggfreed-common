package com.ziggfreed.common.instance.dungeon;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.result.ColumnFormat;
import com.ziggfreed.common.instance.result.ResultKind;
import com.ziggfreed.common.instance.result.ResultsMessages;

/**
 * The words of a dungeon's results screen, so it reads on a server running nothing but this library: every line
 * is a {@code results.*} key in {@code ziggfreedcommon.dungeon.lang}, resolved on the reader's client, so this
 * provider never knows a locale. Immutable; one instance serves every screen.
 *
 * <p>The page paints most of these on {@code .Text} sinks, which resolve a translation but never fill a parameter,
 * so each is a bare key; the one line that carries a number, the run's length, is joined from a bare key and the
 * time as written, which the page flattens on the server. The two score lines go on {@code TextSpans}.
 */
public final class DungeonResultsMessages implements ResultsMessages {

    /** The library's own words. */
    public static final DungeonResultsMessages LIBRARY = new DungeonResultsMessages();

    private DungeonResultsMessages() {
    }

    @Override
    @Nonnull
    public Message outcomeTitle(@Nonnull ResultKind kind) {
        return line(kind == ResultKind.WIN ? "results.cleared" : "results.failed");
    }

    @Override
    @Nonnull
    public Message duration(int seconds) {
        return Msg.join(line("results.time"), Msg.raw(" " + ColumnFormat.TIME.render(seconds)));
    }

    @Override
    @Nonnull
    public Message breakdownTitle() {
        return line("results.breakdown");
    }

    /** The second section: what the party did together. */
    @Override
    @Nonnull
    public Message statsTitle() {
        return line("results.party");
    }

    @Override
    @Nonnull
    public Message rewardsTitle() {
        return line("results.rewards");
    }

    @Override
    @Nonnull
    public Message noRewards() {
        return line("results.no_rewards");
    }

    @Override
    @Nonnull
    public Message pendingNote() {
        return line("results.pending");
    }

    @Override
    @Nonnull
    public Message claimButton() {
        return line("results.claim");
    }

    @Override
    @Nonnull
    public Message claimedNote() {
        return line("results.claimed");
    }

    @Override
    @Nonnull
    public Message viewLeaderboardButton() {
        return line("results.records");
    }

    @Override
    @Nonnull
    public Message playAgainButton() {
        return line("results.again");
    }

    @Override
    @Nonnull
    public Message closeButton() {
        return line("results.close");
    }

    @Override
    @Nonnull
    public Message teamTotal(long total) {
        return Msg.key(DungeonResults.PREFIX + "results.team_total", total);
    }

    /** The label of a member's damage to the run's bosses, in their own breakdown. */
    @Nonnull
    public Message damageLabel() {
        return line("results.damage");
    }

    /** The label of how many boss fights the party won during the run, in each member's own breakdown. */
    @Nonnull
    public Message bossesLabel() {
        return line("results.bosses");
    }

    @Nonnull
    private static Message line(@Nonnull String key) {
        return Msg.key(DungeonResults.PREFIX + key);
    }
}
