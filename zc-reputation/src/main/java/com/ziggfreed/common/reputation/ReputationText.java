package com.ziggfreed.common.reputation;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every line the module says, and the rule it says them by: a sentence is a KEY the reader's own client
 * resolves (in {@code ziggfreedcommon.reputation.lang}, written without the file's prefix), a reputation
 * or rank name a pack authored goes through {@link ContentKeys}, and a number is a typed param.
 */
public final class ReputationText {

    /** The key family every line resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.reputation.";

    /** zc's own seven rank ids, lower-cased: the ranks the library ships a name for. */
    static final List<String> LADDER_IDS = List.of(
            "hated", "unfriendly", "neutral", "friendly", "honored", "revered", "exalted");

    /** Every key the code speaks, for the test that holds the English file to it. */
    public static final List<String> SPOKEN = List.of(
            "rank.hated", "rank.unfriendly", "rank.neutral", "rank.friendly", "rank.honored", "rank.revered",
            "rank.exalted", "rank.none",
            "factor.rank_with",
            "reward.gain", "reward.loss",
            "hud.caption",
            "page.title", "page.empty", "page.row", "page.row.next", "page.row.reward", "page.row.gear",
            "page.row.gear.loss", "page.badge",
            "detail.earned", "detail.gear", "detail.gear.loss", "detail.next", "detail.reward", "detail.top",
            "detail.cap", "menu.tab");

    private ReputationText() {
    }

    /** A line of this family as a client-resolved message. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    /** What the reputation is called: its authored key, else its id as plain text. */
    @Nonnull
    public static Message name(@Nonnull ReputationDef reputation) {
        String key = reputation.titleKey();
        return key == null ? Msg.raw(reputation.id()) : ContentKeys.tr(key);
    }

    /** Its description, or null when none is authored. */
    @Nullable
    public static Message description(@Nonnull ReputationDef reputation) {
        String key = reputation.flavorKey();
        return key == null ? null : ContentKeys.tr(key);
    }

    /**
     * What {@code reputation} calls {@code rank}: its own name for the rank, else the library's word for one
     * of its seven, else the rank's id; {@code rank.none} when there is no rank at all.
     */
    @Nonnull
    public static Message rankName(@Nullable ReputationDef reputation, @Nullable ReputationLadder.Rank rank) {
        if (rank == null) {
            return line("rank.none");
        }
        String authored = reputation == null ? null : reputation.rankNameKey(rank.id());
        if (authored != null) {
            return ContentKeys.tr(authored);
        }
        String own = rank.id().trim().toLowerCase(Locale.ROOT);
        return LADDER_IDS.contains(own) ? line("rank." + own) : Msg.raw(rank.id());
    }
}
