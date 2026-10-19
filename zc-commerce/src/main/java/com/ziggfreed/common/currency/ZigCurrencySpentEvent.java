package com.ziggfreed.common.currency;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.subject.Subject;

/**
 * A balance was genuinely SPENT: the native face of {@link CurrencyObserver#spent}, carrying exactly
 * what an observer is handed.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired by
 * {@link CurrencyEngine#debit} once the whole amount was taken and the spend recorded, right after the
 * engine's own observer heard it, on the thread that moved the balance. A refused debit fires nothing,
 * and neither does a loss (death loss, decay). A charge a failed purchase later refunds has still been
 * announced here: the refund is silent by the engine's rule. Built only when somebody is listening
 * ({@link CurrencyEvents}).
 */
public final class ZigCurrencySpentEvent implements IEvent<Void> {

    private final Subject subject;
    private final CurrencyDef currency;
    private final long amount;

    public ZigCurrencySpentEvent(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount) {
        this.subject = subject;
        this.currency = currency;
        this.amount = amount;
    }

    /** Who paid, with whatever handle the caller attached. */
    @Nonnull
    public Subject subject() {
        return subject;
    }

    /** The subject's id: the player's uuid for a player. */
    @Nonnull
    public UUID playerId() {
        return subject.id();
    }

    /** The wallet, as the catalogue defines it. */
    @Nonnull
    public CurrencyDef currency() {
        return currency;
    }

    /** The wallet id, in the catalogue's own spelling. */
    @Nonnull
    public String currencyId() {
        return currency.id();
    }

    /** What was paid. */
    public long amount() {
        return amount;
    }
}
