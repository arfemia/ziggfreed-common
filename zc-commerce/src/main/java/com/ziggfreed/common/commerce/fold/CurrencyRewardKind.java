package com.ziggfreed.common.commerce.fold;

import javax.annotation.Nonnull;

import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * The reward kind that pays a wallet:
 * {@code {"Kind": "Currency", "Params": {"Currency": "Bounty_Token", "Amount": "300"}}}.
 *
 * <p>It is UNPREFIXED because the library owns the engine behind it, the same reason the shared
 * {@code Effect} kind is: a namespace names the vocabulary's owner, so a mod prefix here would be a
 * false statement about who decides what a currency grant means the day a second consumer ships one.
 *
 * <p>It sits in the join layer rather than beside the currency engine for the rule that layer exists
 * to keep: the engine half holds runtime values and knows nothing about an asset store, and the
 * wallet a payout names is resolved out of the authored catalogue. Registering it is the wiring
 * root's line, exactly like the effect kind's.
 *
 * <p><b>An undefined wallet FAILS the payout</b>, so the shared issuance pass reports it and the
 * rest of the rewards still land; a wallet at its authored ceiling does NOT, because landing short
 * of a cap is a decision the content made rather than a failure.
 */
public final class CurrencyRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Currency";

    /** Who this registration is attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /** The parameter naming which wallet is paid. */
    private static final String PARAM_CURRENCY = "currency";

    /** The parameter saying how much. */
    private static final String PARAM_AMOUNT = "amount";

    private CurrencyRewardKind() {
    }

    /** Register the currency kind into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new CurrencyRewardKind());
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        String currencyId = spec.paramOr(PARAM_CURRENCY, "").trim();
        if (currencyId.isEmpty()) {
            throw new IllegalStateException("a reward of kind '" + KIND
                    + "' named no wallet - it needs a 'Currency' parameter");
        }
        long amount = spec.longParam(PARAM_AMOUNT, 0L);
        if (amount <= 0L) {
            throw new IllegalStateException("a reward of kind '" + KIND + "' pays '" + currencyId
                    + "' an amount of " + amount + ", so it would hand over nothing");
        }
        CurrencyEngine engine = CommerceDefaults.currencyEngine();
        CurrencyDef def = engine.definition(currencyId);
        if (def == null) {
            throw new IllegalStateException("a reward of kind '" + KIND + "' pays the wallet '"
                    + currencyId + "', which nothing on this server defines");
        }
        engine.credit(subject, currencyId, amount);
    }
}
