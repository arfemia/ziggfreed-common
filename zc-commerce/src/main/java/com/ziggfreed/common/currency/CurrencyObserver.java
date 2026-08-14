package com.ziggfreed.common.currency;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * The two currency moments a consumer may want to hear about: a balance genuinely EARNED, and a
 * balance genuinely SPENT.
 *
 * <p>Announcing them is a courtesy the engine offers rather than work it does. What a mod does with
 * an earn - a toast, a statistic, an objective, a native event dispatched to whoever is listening -
 * is that mod's vocabulary, and modelling any of it here would be the engine learning a domain.
 *
 * <p><b>A REFUND is not an earn, and neither is a rollback.</b> Only a payout the player was meant
 * to receive fires {@link #earned}, and only a price they genuinely paid fires {@link #spent}. A
 * compensating refund puts the balance back silently, because a consumer counting earnings must not
 * count a purchase that failed as income, and a half-taken multi-currency price that rolled back
 * must not read as a spend.
 *
 * <p>Every call is guarded by the engine: a throwing observer is reported once and never reaches
 * the transaction that was running.
 */
public interface CurrencyObserver {

    /** Hears nothing. The default, so an engine wired with no observer still runs. */
    CurrencyObserver NONE = new CurrencyObserver() {
    };

    /** {@code amount} of {@code currencyId} reached {@code subject}, leaving them at {@code balance}. */
    default void earned(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount, long balance) {
    }

    /** {@code amount} of {@code currencyId} was paid by {@code subject} and is not coming back. */
    default void spent(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount) {
    }
}
