package com.ziggfreed.common.currency.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.validation.Finding;

/**
 * Audits folded wallet definitions for the mistakes that produce NO error at runtime: a wallet
 * nobody can see, a colour nothing can render, a decay rate that empties a hoard overnight. Every one
 * of them ships as an economy that quietly does not behave the way its author read it, which is far
 * harder to chase than a finding at load.
 *
 * <p>Findings are shared {@link Finding} values under domain {@code commerce}, so a consumer folds
 * them into its own report beside every other validator's.
 */
public final class CurrencyValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "commerce";

    /** A decay rate above this much per day is almost certainly a fraction written as a percent. */
    private static final double SUSPICIOUS_DECAY_PER_DAY = 0.5;

    private CurrencyValidator() {
    }

    /** Audit every folded wallet. */
    @Nonnull
    public static List<Finding> validateAll(@Nonnull Map<String, CurrencyAsset> currencies) {
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, CurrencyAsset> entry : currencies.entrySet()) {
            if (entry.getValue() != null) {
                out.addAll(validate(entry.getKey(), entry.getValue()));
            }
        }
        return out;
    }

    /** Audit one wallet. */
    @Nonnull
    public static List<Finding> validate(@Nonnull String id, @Nonnull CurrencyAsset currency) {
        List<Finding> out = new ArrayList<>();

        if (currency.effectiveIconItemId() == null) {
            out.add(Finding.warning(DOMAIN, "NO_ICON",
                    "no Icon is authored and there is no backing item to take one from, so this wallet shows as "
                            + "a bare number wherever a balance or a price is listed; author Icon with the item "
                            + "whose picture should stand for it", id));
        }
        String color = currency.getColor();
        if (color != null && !isSixDigitHex(color)) {
            out.add(Finding.warning(DOMAIN, "BAD_COLOR",
                    "Color is '" + color + "', which is not a six-digit hex value like #ffcc44, so it is ignored "
                            + "and the balance renders in the surrounding text colour", id));
        }
        Long cap = currency.getCap();
        if (cap != null && cap < 0L) {
            out.add(Finding.warning(DOMAIN, "NEGATIVE_CAP",
                    "Cap is " + cap + "; a ceiling below zero cannot hold anything, so it is read as no ceiling "
                            + "at all. Author 0 when you mean uncapped", id));
        }

        CurrencyAsset.OnDeath onDeath = currency.getOnDeath();
        Double loss = onDeath == null ? null : onDeath.getLossPercent();
        if (loss != null && (loss < 0.0 || loss > 1.0)) {
            out.add(Finding.warning(DOMAIN, "SHARE_OUT_OF_RANGE",
                    "OnDeath.LossPercent is " + loss + "; it is a SHARE of the balance between 0 and 1, so 10 "
                            + "per cent is 0.1 rather than 10. It is clamped into range, which is unlikely to be "
                            + "the number you meant", id));
        }
        CurrencyAsset.Decay decay = currency.getDecay();
        Double perDay = decay == null ? null : decay.getPerDayPercent();
        if (perDay != null && (perDay < 0.0 || perDay > 1.0)) {
            out.add(Finding.warning(DOMAIN, "SHARE_OUT_OF_RANGE",
                    "Decay.PerDayPercent is " + perDay + "; it is a SHARE of the balance between 0 and 1, so one "
                            + "per cent a day is 0.01 rather than 1. It is clamped into range, which is unlikely "
                            + "to be the number you meant", id));
        } else if (perDay != null && perDay > SUSPICIOUS_DECAY_PER_DAY) {
            out.add(Finding.warning(DOMAIN, "STEEP_DECAY",
                    "Decay.PerDayPercent is " + perDay + ", which wears away most of a balance for every day a "
                            + "player is away; a week off leaves almost nothing. Check the number is the share "
                            + "you meant", id));
        }

        if (currency.isItemBacked() && currency.getCap() != null && currency.cap() > 0L) {
            out.add(Finding.info(DOMAIN, "CAP_ON_ITEM_BACKED",
                    "Cap is authored on a wallet whose balance is an inventory count, so what a player may hold "
                            + "is already whatever their inventory holds; the ceiling only ever stops them being "
                            + "GIVEN more", id));
        }
        return out;
    }

    /** Is {@code value} a leading-hash six-digit hex colour? */
    private static boolean isSixDigitHex(@Nullable String value) {
        if (value == null || value.length() != 7 || value.charAt(0) != '#') {
            return false;
        }
        for (int i = 1; i < 7; i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
