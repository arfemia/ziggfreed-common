package com.ziggfreed.common.commerce.fold;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.command.CommerceCommandLine;
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
 *
 * <p><b>A failed payout is replayable</b>: {@link #retryCommand} answers the admin command line that
 * would credit the same wallet the same amount, so a consumer's retry queue hands it over on the
 * player's next connect instead of the payout being lost. It is null for exactly the specs
 * {@link #grant} refuses, because a reward that cannot say what it pays is not replayable either.
 */
public final class CurrencyRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Currency";

    /** Who this registration is attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /** The parameter naming which wallet is paid. */
    static final String PARAM_CURRENCY = "currency";

    /**
     * The older spelling of that parameter, still read.
     *
     * <p>A consumer arriving from its own wallet system will have content written this way, and the
     * failure it causes is the quiet kind: the file loads, a validator passes it, a preview promises
     * the payout, and only the handler finds nothing named. Reading both spellings costs one map
     * lookup and removes that whole class of report, whatever a consumer's own parse seam does.
     */
    static final String PARAM_CURRENCY_LEGACY = "currencyid";

    /** The parameter saying how much. */
    private static final String PARAM_AMOUNT = "amount";

    private CurrencyRewardKind() {
    }

    /** Register the currency kind into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new CurrencyRewardKind());
    }

    /**
     * Which wallet {@code spec} pays, in either spelling, trimmed; empty when it names none.
     *
     * <p>Public because a chip painted for a currency reward has to read the same parameter the
     * payout does. Two readers disagreeing is the shape where a screen promises a payout the grant
     * then refuses, and one method is what makes that impossible.
     */
    @Nonnull
    public static String walletOf(@Nonnull RewardSpec spec) {
        String current = spec.paramOr(PARAM_CURRENCY, "").trim();
        return current.isEmpty() ? spec.paramOr(PARAM_CURRENCY_LEGACY, "").trim() : current;
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        String currencyId = walletOf(spec);
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

    /**
     * The admin line that would pay the same wallet the same amount later.
     *
     * <p>It is built from the SPEC rather than from anything the failed attempt learned, so a queued
     * retry hands over exactly what the content authored. A spec {@link #grant} would refuse - no
     * wallet named, nothing to hand over - answers null instead, so it is reported lost rather than
     * parked in a queue that would refuse it again on every attempt.
     *
     * <p>The wallet is deliberately NOT checked here: a payout can fail because the pack defining
     * that wallet had not finished loading, and refusing to queue would turn a recoverable moment
     * into a lost reward. A retry naming a wallet that never appears refuses at the command, once,
     * where it is visible.
     */
    @Override
    @Nullable
    public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nonnull String sourceId) {
        String currencyId = walletOf(spec);
        long amount = spec.longParam(PARAM_AMOUNT, 0L);
        if (currencyId.isEmpty() || amount <= 0L) {
            return null;
        }
        return CommerceCommandLine.give(subject.name(), currencyId, amount);
    }
}
