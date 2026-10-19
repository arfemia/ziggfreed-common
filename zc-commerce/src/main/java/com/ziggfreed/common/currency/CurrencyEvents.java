package com.ziggfreed.common.currency;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.subject.Subject;

/**
 * Where the currency family's two native events go ({@link ZigCurrencyEarnedEvent},
 * {@link ZigCurrencySpentEvent}): the shared engine event bus, under the library-wide contract
 * ({@link NativeEventSeam}).
 *
 * <p>Fired by {@link CurrencyEngine} alone, after its observer, so every engine (the library's
 * default and a consumer's replacement alike) announces identically. Each event is built only when
 * somebody is listening and can never reach the transaction that fired it. A third party listens on
 * the bus with no compile-time dependency beyond the event classes; a host running without a bus (a
 * harness, a unit JVM) redirects the family through {@link #publishTo}.
 */
public final class CurrencyEvents {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[currency]");

    private CurrencyEvents() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host outside a Hytale server and for a test observing what the engine fires. Not for a
     * mod: on a live server the bus is the one place a listener looks.
     */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    static void fireEarned(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount, long balance) {
        if (SEAM.listening(ZigCurrencyEarnedEvent.class)) {
            SEAM.fire("ZigCurrencyEarned", ZigCurrencyEarnedEvent.class,
                    () -> new ZigCurrencyEarnedEvent(subject, currency, amount, balance));
        }
    }

    static void fireSpent(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount) {
        if (SEAM.listening(ZigCurrencySpentEvent.class)) {
            SEAM.fire("ZigCurrencySpent", ZigCurrencySpentEvent.class,
                    () -> new ZigCurrencySpentEvent(subject, currency, amount));
        }
    }
}
