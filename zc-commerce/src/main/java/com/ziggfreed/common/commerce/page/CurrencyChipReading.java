package com.ziggfreed.common.commerce.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.fold.CommerceDefaults;
import com.ziggfreed.common.commerce.fold.CurrencyRewardKind;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * How a {@code Currency} reward READS, contributed process-wide so no reward ever has to say it:
 * the wallet's own icon, and its amount beside the wallet's own name on the same three-rung ladder
 * every commerce screen already uses (an authored {@code Text.TitleKey}, else the convention key,
 * else the backing item's native name).
 *
 * <p>It exists because the kind is Java-registered: there is no kind FILE to carry a
 * {@code Presentation}, and asking every quest, achievement and offer that pays a wallet to author
 * a {@code NameKey} would put a currency's display name in a hundred places instead of the one
 * file that owns it. Contributed through {@link RewardChips#contribute}, so it answers only where
 * the generic reading found nothing - a reward's own {@code NameKey}, when one IS authored, still
 * wins.
 *
 * <p>A wallet no layer defines answers null and the chip stays dropped, because naming a wallet
 * that does not exist on this server is the exact promise the drop rule exists to prevent.
 */
public final class CurrencyChipReading {

    private CurrencyChipReading() {
    }

    /** The reading; the wiring root contributes it once at setup. */
    @Nonnull
    public static RewardChips.Source source() {
        return CurrencyChipReading::chipFor;
    }

    @Nullable
    private static RewardChip chipFor(@Nonnull RewardSpec spec) {
        if (!CurrencyRewardKind.KIND.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        // The same read the PAYOUT makes, both spellings included: a chip and a grant disagreeing is
        // how a screen comes to promise something the handler then refuses.
        String currencyId = CurrencyRewardKind.walletOf(spec);
        if (currencyId.isEmpty()) {
            return null;
        }
        CurrencyEngine currencies = CommerceDefaults.currencyEngine();
        CurrencyDef def = currencies.catalog().get(currencyId);
        if (def == null) {
            return null;
        }
        long amount = RewardChips.amountOf(spec);
        // The CONSUMER's naming rung, not null: a wallet's convention key belongs to whoever ships
        // the lang file that registers it, and only the consumer knows the prefix its own filename
        // gave every key in it. Passing null here drops straight to the authored ladder, which emits
        // the bare key as a message id nothing resolves - so the chip paints the key itself.
        // Read at CHIP time rather than captured, so the consumer's deps are in force by then.
        return RewardChip.of(CurrencyText.iconOf(def), CommerceChips.amountAndName(
                currencies, currencyId, amount, CommercePages.resolvedDeps().currencyNames()));
    }
}
