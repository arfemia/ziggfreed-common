package com.ziggfreed.common.currency;

import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.CommerceStore;
import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Reads and moves a subject's currency balances. The ONE place the item-or-counter question is
 * asked, so nothing above it ever branches on how a currency is backed.
 *
 * <p>Four operations and one rule each:
 * <ul>
 *   <li>{@link #balance} answers what they hold, 0 for a currency nobody defined.</li>
 *   <li>{@link #credit} adds and answers the NEW balance, never crossing the cap.</li>
 *   <li>{@link #debit} takes exactly what was asked or nothing at all, and answers which.</li>
 *   <li>{@link #refund} puts back what a failed transaction charged, silently.</li>
 * </ul>
 *
 * <p><b>An unknown currency is inert, not an error.</b> Content naming a currency whose pack is not
 * installed reads 0, credits nothing and debits nothing, with one line saying so. That is the
 * library's standing unknown-id rule, and it is what lets one authored file be correct on a server
 * with the pack and on a server without it.
 *
 * <p><b>The engine holds no clock.</b> {@link #applyDecay} takes the elapsed days and
 * {@link #applyDeathLoss} takes nothing at all, so the passes are exercisable by handing them
 * numbers and a consumer's tick decides when they run.
 *
 * <p>Experience conversion is deliberately NOT here. What a mod's experience is worth is that mod's
 * vocabulary: it reads its own knob off {@link CurrencyDef#meta} and calls {@link #credit}.
 */
public final class CurrencyEngine {

    private final CurrencyCatalog catalog;
    private final ItemWallet items;
    private final Supplier<CommerceStore> store;
    private final CurrencyObserver observer;
    private final Consumer<String> warn;

    private CurrencyEngine(@Nonnull Builder b) {
        this.catalog = b.catalog;
        this.items = b.items;
        this.store = b.store;
        this.observer = b.observer;
        this.warn = b.warn;
    }

    /** The definitions this engine reads. */
    @Nonnull
    public CurrencyCatalog catalog() {
        return catalog;
    }

    /** The definition for {@code currencyId}, or null when nobody defines it. */
    @Nullable
    public CurrencyDef definition(@Nonnull String currencyId) {
        return catalog.get(currencyId);
    }

    // ==================== Read ====================

    /**
     * What {@code subject} holds of {@code currencyId}: their inventory count when it is
     * item-backed, their stored counter when it is not. Zero for an unknown currency.
     */
    public long balance(@Nonnull Subject subject, @Nonnull String currencyId) {
        CurrencyDef def = catalog.get(currencyId);
        return def == null ? 0L : balance(subject, def);
    }

    /** {@link #balance} for a definition already in hand, skipping the lookup. */
    public long balance(@Nonnull Subject subject, @Nonnull CurrencyDef def) {
        if (def.isItemBacked()) {
            return Math.max(0L, items.count(subject, def.backingItemId()));
        }
        return Math.max(0L, store.get().balance(subject, def.id()));
    }

    /** Can {@code subject} pay {@code amount} of {@code currencyId} right now? */
    public boolean canAfford(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        return amount <= 0L || balance(subject, currencyId) >= amount;
    }

    /**
     * How much of {@code currencyId} this subject has spent in its lifetime, and 0 for a currency
     * nobody defines.
     *
     * <p>The DEFINITION is resolved first, exactly as {@link #balance} does, because a spend is
     * recorded under the catalogue's own spelling of the id and the store behind it matches keys
     * literally. Passing the caller's raw spelling through would answer 0 for a gate written
     * {@code Bounty_Token} while the same gate written {@code bounty_token} read correctly - one id
     * resolving two ways, and the wrong answer is the one that looks like an unmet requirement.
     */
    public long lifetimeSpent(@Nonnull Subject subject, @Nonnull String currencyId) {
        CurrencyDef def = catalog.get(currencyId);
        return def == null ? 0L : store.get().lifetimeSpent(subject, def.id());
    }

    // ==================== Write ====================

    /**
     * Add {@code amount} to this subject's balance and answer the new one. A cap is honoured, so a
     * credit that would cross it lands short and the answer says how far it got; a non-positive
     * amount changes nothing.
     *
     * <p>Fires {@link CurrencyObserver#earned} with the delta that genuinely landed, never with
     * what was asked for. Use {@link #refund} instead when putting back a charge.
     */
    public long credit(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        CurrencyDef def = catalog.get(currencyId);
        if (def == null) {
            unknown("credit", currencyId);
            return 0L;
        }
        long before = balance(subject, def);
        long after = creditQuietly(subject, def, amount);
        long delta = after - before;
        if (delta > 0L) {
            guarded(() -> observer.earned(subject, def, delta, after), "earned", def.id());
        }
        return after;
    }

    /**
     * Take exactly {@code amount}, or nothing. False means the balance is untouched, so a caller
     * charging several components can compensate the ones it already took.
     *
     * <p>A successful take records the spend on the store and fires
     * {@link CurrencyObserver#spent}; a refused one does neither.
     */
    public boolean debit(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        if (amount <= 0L) {
            return true;
        }
        CurrencyDef def = catalog.get(currencyId);
        if (def == null) {
            unknown("debit", currencyId);
            return false;
        }
        boolean taken = def.isItemBacked()
                ? items.take(subject, def.backingItemId(), amount)
                : takeCounter(subject, def, amount);
        if (!taken) {
            return false;
        }
        store.get().recordSpend(subject, def.id(), amount);
        guarded(() -> observer.spent(subject, def, amount), "spent", def.id());
        return true;
    }

    /**
     * Put back {@code amount} a charge took, and undo its share of the lifetime spend. Deliberately
     * SILENT: a refund is money returning to where it was, not income, so nothing that counts
     * earnings should see it.
     */
    public long refund(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        CurrencyDef def = catalog.get(currencyId);
        if (def == null) {
            unknown("refund", currencyId);
            return 0L;
        }
        long after = creditQuietly(subject, def, amount);
        store.get().refundSpend(subject, def.id(), amount);
        return after;
    }

    /**
     * Set the balance outright, clamped to the cap. The admin operation, and the one write that is
     * not part of an exchange, so it announces nothing.
     */
    public long set(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        CurrencyDef def = catalog.get(currencyId);
        if (def == null) {
            unknown("set", currencyId);
            return 0L;
        }
        long target = clampToCap(def, Math.max(0L, amount));
        if (!def.isItemBacked()) {
            store.get().setBalance(subject, def.id(), target);
            return target;
        }
        long current = balance(subject, def);
        if (current > target) {
            items.take(subject, def.backingItemId(), current - target);
        } else if (current < target) {
            items.give(subject, def.backingItemId(), target - current);
        }
        return balance(subject, def);
    }

    // ==================== Economy passes ====================

    /**
     * Take each currency's authored death loss off this subject, rounding UP so a tiny balance with
     * a real loss percentage still loses something. Currencies with no loss authored are untouched,
     * and so is a balance of zero.
     *
     * @return how many currencies actually lost something
     */
    public int applyDeathLoss(@Nonnull Subject subject) {
        int touched = 0;
        for (CurrencyDef def : catalog.all()) {
            double fraction = def.lossOnDeathPercent();
            if (fraction <= 0.0) {
                continue;
            }
            long current = balance(subject, def);
            if (current <= 0L) {
                continue;
            }
            long lost = (long) Math.ceil(current * fraction);
            if (lost > 0L && debitQuietly(subject, def, Math.min(lost, current))) {
                touched++;
            }
        }
        return touched;
    }

    /**
     * Compound each currency's authored daily decay over {@code daysElapsed} and take the
     * difference. The caller supplies the elapsed days, because how long somebody was away is a
     * question about a session rather than about a wallet.
     *
     * @return how many currencies actually decayed
     */
    public int applyDecay(@Nonnull Subject subject, long daysElapsed) {
        if (daysElapsed <= 0L) {
            return 0;
        }
        int touched = 0;
        for (CurrencyDef def : catalog.all()) {
            double daily = def.decayPerDayPercent();
            if (daily <= 0.0) {
                continue;
            }
            long current = balance(subject, def);
            if (current <= 0L) {
                continue;
            }
            double factor = Math.pow(1.0 - daily, daysElapsed);
            long retained = Math.max(0L, (long) Math.floor(current * factor));
            long lost = current - retained;
            if (lost > 0L && debitQuietly(subject, def, lost)) {
                touched++;
            }
        }
        return touched;
    }

    // ==================== Internals ====================

    /** The cap-honouring add, with no announcement. Both {@link #credit} and {@link #refund} use it. */
    private long creditQuietly(@Nonnull Subject subject, @Nonnull CurrencyDef def, long amount) {
        if (amount <= 0L) {
            return balance(subject, def);
        }
        long current = balance(subject, def);
        long allowed = amount;
        if (!def.isUncapped()) {
            long room = def.cap() - current;
            if (room <= 0L) {
                return current;
            }
            allowed = Math.min(allowed, room);
        }
        if (def.isItemBacked()) {
            items.give(subject, def.backingItemId(), allowed);
            return balance(subject, def);
        }
        long next = current + allowed;
        store.get().setBalance(subject, def.id(), next);
        return next;
    }

    /** A take that is a LOSS rather than a purchase: no spend record, no observer. */
    private boolean debitQuietly(@Nonnull Subject subject, @Nonnull CurrencyDef def, long amount) {
        if (amount <= 0L) {
            return false;
        }
        return def.isItemBacked()
                ? items.take(subject, def.backingItemId(), amount)
                : takeCounter(subject, def, amount);
    }

    private boolean takeCounter(@Nonnull Subject subject, @Nonnull CurrencyDef def, long amount) {
        CommerceStore state = store.get();
        long current = Math.max(0L, state.balance(subject, def.id()));
        if (current < amount) {
            return false;
        }
        state.setBalance(subject, def.id(), current - amount);
        return true;
    }

    private static long clampToCap(@Nonnull CurrencyDef def, long value) {
        return def.isUncapped() ? value : Math.min(value, def.cap());
    }

    private void unknown(@Nonnull String op, @Nonnull String currencyId) {
        warn.accept("[currency] " + op + " on '" + currencyId + "', which nothing defines - no balance moved");
    }

    private void guarded(@Nonnull Runnable body, @Nonnull String what, @Nonnull String currencyId) {
        try {
            body.run();
        } catch (Throwable t) {
            warn.accept("[currency] an observer threw on " + what + " for '" + currencyId
                    + "', which changed no balance: " + t.getMessage());
        }
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** Assembles a {@link CurrencyEngine}; every seam has a working default but the catalog. */
    public static final class Builder {

        private CurrencyCatalog catalog = CurrencyCatalog.EMPTY;
        private ItemWallet items = NativeItemWallet.INSTANCE;
        private Supplier<CommerceStore> store = CommerceStores::get;
        private CurrencyObserver observer = CurrencyObserver.NONE;
        private Consumer<String> warn = SafeLog::warn;

        private Builder() {
        }

        /** Which currencies exist. Unset means none, so every operation is inert. */
        @Nonnull
        public Builder catalog(@Nonnull CurrencyCatalog catalog) {
            this.catalog = catalog;
            return this;
        }

        /** Where an item-backed balance is read and moved. Defaults to the real inventory. */
        @Nonnull
        public Builder items(@Nonnull ItemWallet items) {
            this.items = items;
            return this;
        }

        /** Where a counter-backed balance lives. Defaults to whatever is installed at call time. */
        @Nonnull
        public Builder store(@Nonnull Supplier<CommerceStore> store) {
            this.store = store;
            return this;
        }

        /** A fixed store, for a test that wants to hold the instance it drives. */
        @Nonnull
        public Builder store(@Nonnull CommerceStore store) {
            this.store = () -> store;
            return this;
        }

        /** Who hears about earns and spends. Unset hears nothing. */
        @Nonnull
        public Builder observer(@Nonnull CurrencyObserver observer) {
            this.observer = observer;
            return this;
        }

        /** Where an unknown currency and a throwing observer are reported. */
        @Nonnull
        public Builder warn(@Nonnull Consumer<String> warn) {
            this.warn = warn;
            return this;
        }

        @Nonnull
        public CurrencyEngine build() {
            return new CurrencyEngine(this);
        }
    }
}
