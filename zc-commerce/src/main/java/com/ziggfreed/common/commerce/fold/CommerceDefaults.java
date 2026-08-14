package com.ziggfreed.common.commerce.fold;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a BARE server's economy runs on: the state store nothing has replaced yet, and the currency
 * engine reading the wallets its own packs authored.
 *
 * <p>Both are producer REPLACEMENT rather than layering. A consumer keeping this state itself
 * installs its own at setup and the previous one stops being asked, so two stores holding two
 * versions of one wallet cannot exist. Common's own {@code setup()} runs first (every consumer
 * declares it as a dependency, so the server loads it first), which is what makes installing the
 * defaults here safe rather than a clobber.
 *
 * <p>The store default is deliberately said out loud rather than left implicit: a server whose
 * purchases stopped surviving a restart should be able to find out why from its boot log.
 */
public final class CommerceDefaults {

    private static final AtomicReference<CurrencyEngine> CURRENCY = new AtomicReference<>();

    private CommerceDefaults() {
    }

    /**
     * Install the defaults, once, from the wiring root's {@code setup()}: the in-memory state store
     * and the currency engine over the authored wallets.
     */
    public static void install() {
        CommerceStores.install(new InMemoryCommerceStore());
        installCurrencyEngine(assetBacked());
        SafeLog.info("[commerce] economy ready: wallets, prices and rotations read the authored "
                + "content, and commerce state is kept in memory until something persistent is installed");
    }

    /**
     * Replace the currency engine every commerce surface reads through. Passing null restores the
     * default one over the authored wallets.
     */
    public static void installCurrencyEngine(@Nullable CurrencyEngine engine) {
        CURRENCY.set(engine);
    }

    /**
     * The currency engine in force. Never null: a caller reaching for it before anything installed
     * one - an early command, a test - gets the default over the authored wallets rather than
     * having to guard.
     */
    @Nonnull
    public static CurrencyEngine currencyEngine() {
        return CURRENCY.updateAndGet(current -> current != null ? current : assetBacked());
    }

    /**
     * The default engine: the authored wallets, the real inventory behind an item-backed balance, and
     * whichever state store is installed AT CALL TIME (never captured, because a consumer's setup may
     * run after this).
     */
    @Nonnull
    private static CurrencyEngine assetBacked() {
        return CurrencyEngine.builder().catalog(CommerceCatalogs.currencies()).build();
    }
}
