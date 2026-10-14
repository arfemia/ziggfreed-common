package com.ziggfreed.common.currency;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * Two observers in one slot, built by {@link CurrencyObserver#both}: the first is told, then the second,
 * and the second still hears a moment the first threw on. The first one's failure is rethrown after the
 * second ran (the second's attached to it), so the engine's own guard reports it as before.
 */
final class ObserverPair implements CurrencyObserver {

    private final CurrencyObserver first;
    private final CurrencyObserver second;

    ObserverPair(@Nonnull CurrencyObserver first, @Nonnull CurrencyObserver second) {
        this.first = first;
        this.second = second;
    }

    /** Does {@code observer} already tell {@code wanted}, itself or anywhere inside a pair? */
    static boolean includes(@Nonnull CurrencyObserver observer, @Nonnull CurrencyObserver wanted) {
        if (observer == wanted) {
            return true;
        }
        return observer instanceof ObserverPair pair
                && (includes(pair.first, wanted) || includes(pair.second, wanted));
    }

    @Override
    public void earned(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount, long balance) {
        Throwable failed = null;
        try {
            first.earned(subject, currency, amount, balance);
        } catch (Throwable t) {
            failed = t;
        }
        try {
            second.earned(subject, currency, amount, balance);
        } catch (Throwable t) {
            failed = attach(failed, t);
        }
        rethrow(failed);
    }

    @Override
    public void spent(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount) {
        Throwable failed = null;
        try {
            first.spent(subject, currency, amount);
        } catch (Throwable t) {
            failed = t;
        }
        try {
            second.spent(subject, currency, amount);
        } catch (Throwable t) {
            failed = attach(failed, t);
        }
        rethrow(failed);
    }

    @Nonnull
    private static Throwable attach(@Nullable Throwable earlier, @Nonnull Throwable later) {
        if (earlier == null) {
            return later;
        }
        earlier.addSuppressed(later);
        return earlier;
    }

    private static void rethrow(@Nullable Throwable failed) {
        if (failed instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failed instanceof Error error) {
            throw error;
        }
        if (failed != null) {
            throw new IllegalStateException(failed);
        }
    }
}
