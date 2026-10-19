package com.ziggfreed.common.currency;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.subject.Subject;

/**
 * A balance was genuinely EARNED: the native face of {@link CurrencyObserver#earned}, carrying exactly
 * what an observer is handed.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired by
 * {@link CurrencyEngine#credit} once the credit landed and right after the engine's own observer
 * heard it, on the thread that moved the balance. {@link #amount()} is the delta that really landed (a
 * cap may have cut it short), never what was asked for. A refund, a rollback and an admin set fire
 * nothing, so a listener counting income never counts a purchase that failed. Built only when somebody
 * is listening ({@link CurrencyEvents}).
 */
public final class ZigCurrencyEarnedEvent implements IEvent<Void> {

    private final Subject subject;
    private final CurrencyDef currency;
    private final long amount;
    private final long balance;

    public ZigCurrencyEarnedEvent(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount,
            long balance) {
        this.subject = subject;
        this.currency = currency;
        this.amount = amount;
        this.balance = balance;
    }

    /** Who earned it, with whatever handle the caller attached. */
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

    /** What landed. */
    public long amount() {
        return amount;
    }

    /** The balance the credit left. */
    public long balance() {
        return balance;
    }
}
