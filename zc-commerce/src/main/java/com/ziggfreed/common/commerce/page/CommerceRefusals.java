package com.ziggfreed.common.commerce.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.board.BoardEngine;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.shop.ShopEngine;

/**
 * A refusal TOKEN, turned into a line a player reads.
 *
 * <p>The engines answer in tokens on purpose - {@code "limit:daily"}, {@code "cost:currency:coin"} -
 * because only the surface knows the player's language and its own wording. This is that surface's
 * half, and it lives here rather than inside either page because both pages refuse for the same
 * reasons and a second wording for one sentence is how two screens start disagreeing.
 *
 * <p><b>What gets a line of its own, and what does not.</b> Every token an engine defines is
 * something the player can act on - buy fewer, come back tomorrow, earn more, free a slot - so each
 * has its own key. A GATE refusal does not: it names whatever factor an author gated on
 * ({@code "factor:my_pack:veteran"}), and painting that at a player reads as a promise that a thing
 * called that exists. Those, and anything this library has never heard of, read as the one generic
 * locked line. It is the same rule the objective book and the NPC quest page already follow.
 *
 * <p>Pure: strings in, keys out, no engine and no page, which is what lets the mapping be checked
 * against the engines' own constants with no server standing.
 */
public final class CommerceRefusals {

    /** Shown for a gate refusal, and for any token this library does not recognise. */
    public static final String KEY_LOCKED = "refuse.locked";

    // Shop tokens.
    private static final String KEY_DISABLED = "refuse.disabled";
    private static final String KEY_UNKNOWN_OFFER = "refuse.unknown_offer";
    private static final String KEY_LIMIT_DAILY = "refuse.limit_daily";
    private static final String KEY_LIMIT_TOTAL = "refuse.limit_total";
    private static final String KEY_SHORT_CURRENCY = "refuse.short_currency";
    private static final String KEY_SHORT_ITEM = "refuse.short_item";
    private static final String KEY_NO_ROOM = "refuse.no_room";
    private static final String KEY_CANNOT_PAY = "refuse.cannot_pay";
    private static final String KEY_REFUNDED = "refuse.refunded";

    // Board tokens.
    private static final String KEY_NOT_ON_BOARD = "refuse.not_on_board";
    private static final String KEY_ALREADY_CARRIED = "refuse.already_carried";
    private static final String KEY_SPENT_THIS_PERIOD = "refuse.spent_this_period";
    private static final String KEY_QUEST_REFUSED = "refuse.quest_refused";

    // Reroll tokens, shared by both: the two engines spell them identically, so does this.
    private static final String KEY_NO_REROLL = "refuse.no_reroll";
    private static final String KEY_REROLL_CAP = "refuse.reroll_cap";
    private static final String KEY_REROLL_NO_ALTERNATIVE = "refuse.reroll_no_alternative";
    private static final String KEY_REROLL_CANNOT_PAY = "refuse.reroll_cannot_pay";

    /**
     * One refusal as a surface renders it: which line, and the thing it is about when the token
     * named one.
     *
     * <p>The two ids are what lets a shortfall read as "You need 40 more Bounty Tokens" rather than
     * "You cannot afford that": the page resolves the name itself, in the player's own locale, and
     * nests it as an argument.
     */
    public record Refusal(@Nonnull String key, @Nullable String currencyId, @Nullable String itemId) {

        /** A line with nothing to name. */
        @Nonnull
        static Refusal plain(@Nonnull String key) {
            return new Refusal(key, null, null);
        }

        /** True when this is the generic locked line rather than a refusal of its own. */
        public boolean isGeneric() {
            return KEY_LOCKED.equals(key);
        }
    }

    private CommerceRefusals() {
    }

    /** The line {@code token} reads as, and whatever it named. A null or blank token reads locked. */
    @Nonnull
    public static Refusal of(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return Refusal.plain(KEY_LOCKED);
        }
        String t = token.trim();
        if (t.startsWith(ShopEngine.REASON_SHORT_CURRENCY)) {
            return new Refusal(KEY_SHORT_CURRENCY,
                    trimToNull(t.substring(ShopEngine.REASON_SHORT_CURRENCY.length())), null);
        }
        if (t.startsWith(ShopEngine.REASON_SHORT_ITEM)) {
            return new Refusal(KEY_SHORT_ITEM, null,
                    trimToNull(t.substring(ShopEngine.REASON_SHORT_ITEM.length())));
        }
        return Refusal.plain(keyOf(t));
    }

    /** Just the line, for a caller with nothing to name. */
    @Nonnull
    public static String keyOf(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return KEY_LOCKED;
        }
        String t = token.trim();
        if (t.startsWith(ShopEngine.REASON_SHORT_CURRENCY)) {
            return KEY_SHORT_CURRENCY;
        }
        if (t.startsWith(ShopEngine.REASON_SHORT_ITEM)) {
            return KEY_SHORT_ITEM;
        }
        return switch (t) {
            case ShopEngine.REASON_DISABLED -> KEY_DISABLED;
            case ShopEngine.REASON_UNKNOWN_OFFER -> KEY_UNKNOWN_OFFER;
            case ShopEngine.REASON_LIMIT_DAILY -> KEY_LIMIT_DAILY;
            case ShopEngine.REASON_LIMIT_TOTAL -> KEY_LIMIT_TOTAL;
            case ShopEngine.REASON_NO_ROOM -> KEY_NO_ROOM;
            case ShopEngine.REASON_CANNOT_PAY -> KEY_CANNOT_PAY;
            case ShopEngine.REASON_REFUNDED -> KEY_REFUNDED;
            case BoardEngine.REASON_NOT_ON_BOARD -> KEY_NOT_ON_BOARD;
            case BoardEngine.REASON_ALREADY_CARRIED -> KEY_ALREADY_CARRIED;
            case BoardEngine.REASON_SPENT_THIS_PERIOD -> KEY_SPENT_THIS_PERIOD;
            case BoardEngine.REASON_REFUSED -> KEY_QUEST_REFUSED;
            case ShopEngine.REASON_NO_REROLL -> KEY_NO_REROLL;
            case ShopEngine.REASON_REROLL_CAP -> KEY_REROLL_CAP;
            case ShopEngine.REASON_NO_ALTERNATIVE -> KEY_REROLL_NO_ALTERNATIVE;
            case ShopEngine.REASON_REROLL_CANNOT_PAY -> KEY_REROLL_CANNOT_PAY;
            // A gate names whatever an author gated on, which is not a sentence a player can be
            // shown. Everything below reads as the one locked line, deliberately.
            case GateEvaluator.REASON_PERMISSION, GateEvaluator.REASON_ANY_OF -> KEY_LOCKED;
            default -> KEY_LOCKED;
        };
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
