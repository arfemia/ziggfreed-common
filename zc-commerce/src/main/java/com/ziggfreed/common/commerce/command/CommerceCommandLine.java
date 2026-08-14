package com.ziggfreed.common.commerce.command;

import javax.annotation.Nonnull;

/**
 * What this family is CALLED, and the one line anything else writes to drive it.
 *
 * <p>A leaf on purpose: it imports nothing, so the reward kind that needs a replayable command line
 * can name it without dragging a command implementation - or anything the commands read - into the
 * layer below. The format has exactly one owner, which is the command that parses it, and every
 * producer of that line asks here rather than spelling a second copy that drifts.
 *
 * <p><b>The named-arg form is not a style choice.</b> The engine's parser binds arguments by NAME,
 * so a positional line silently binds nothing.
 */
public final class CommerceCommandLine {

    /** The command family every commerce admin verb hangs off. */
    public static final String FAMILY = "zigcommerce";

    /** Audit every piece of authored commerce content. */
    public static final String VALIDATE = "validate";

    /** List the wallets any layer defines. */
    public static final String WALLETS = "wallets";

    /** List the storefronts, their shelves and what is on them. */
    public static final String SHOPS = "shops";

    /** List the boards, their slots and their rotation. */
    public static final String BOARDS = "boards";

    /** Show one player's whole commerce state. */
    public static final String SHOW = "show";

    /** Add to a player's wallet. */
    public static final String GIVE = "give";

    /** Take from a player's wallet. */
    public static final String TAKE = "take";

    /** Write a player's wallet outright. */
    public static final String SET = "set";

    /** Clear a player's purchase counts. */
    public static final String RESET_LIMITS = "resetlimits";

    /** Clear a player's reroll state. */
    public static final String RESET_REROLLS = "resetrerolls";

    private CommerceCommandLine() {
    }

    /**
     * The console line that credits {@code amount} of {@code currencyId} to {@code player}, which is
     * what a failed wallet payout is replayed through.
     *
     * @param player the target's username, or a placeholder a queue substitutes later
     */
    @Nonnull
    public static String give(@Nonnull String player, @Nonnull String currencyId, long amount) {
        return "/" + FAMILY + " " + GIVE + " --player=" + player + " --currency=" + currencyId
                + " --amount=" + amount;
    }
}
