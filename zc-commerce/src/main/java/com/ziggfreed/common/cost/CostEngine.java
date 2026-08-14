package com.ziggfreed.common.cost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.currency.ItemWallet;
import com.ziggfreed.common.currency.NativeItemWallet;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Checks, charges and gives back a {@link Cost}. The ONE authority on money changing hands, so
 * every priced thing in the library charges identically and every refund puts back exactly what was
 * taken.
 *
 * <p>Three operations:
 * <ul>
 *   <li>{@link #check} answers whether the price can be paid right now, naming the FIRST thing it
 *       falls short on so a caller can say which.</li>
 *   <li>{@link #drain} charges it and hands back a {@link Receipt} of what was genuinely taken.</li>
 *   <li>{@link #refund} takes a receipt and puts that back.</li>
 * </ul>
 *
 * <p><b>Refund the RECEIPT, never the price.</b> An {@code Any} price charges exactly one of its
 * components, so refunding the price would hand back things nobody paid; the receipt is what
 * actually left the wallet, which is why {@link #drain} answers one instead of a boolean.
 *
 * <p><b>An All price never half-charges.</b> Items are verified before any currency moves,
 * currencies are taken with every earlier one put back the moment one fails, and items are removed
 * last with the currencies compensated if they vanish in between. The failure a payer can never
 * undo is the one this ordering exists to make impossible.
 */
public final class CostEngine {

    /** What a price cannot be paid with right now. */
    public record Affordability(boolean ok, @Nullable String shortCurrencyId, @Nullable String shortItemId) {

        /** The price can be paid. */
        public static final Affordability OK = new Affordability(true, null, null);

        /** Short of a currency, named. */
        @Nonnull
        public static Affordability shortCurrency(@Nonnull String currencyId) {
            return new Affordability(false, currencyId, null);
        }

        /** Short of an item, named. */
        @Nonnull
        public static Affordability shortItem(@Nonnull String itemId) {
            return new Affordability(false, null, itemId);
        }

        /** The id of whatever is short, or null when the price can be paid. */
        @Nullable
        public String shortId() {
            return shortCurrencyId != null ? shortCurrencyId : shortItemId;
        }
    }

    /**
     * What a drain genuinely took. {@code paid} is the exact price to hand to {@link #refund}: the
     * whole price for an {@code All} drain, the ONE component charged for an {@code Any} drain, and
     * {@link Cost#FREE} for a free price, whose refund is a no-op.
     */
    public record Receipt(boolean ok, @Nonnull Cost paid) {

        /** Nothing was charged and nothing changed. */
        public static final Receipt FAILED = new Receipt(false, Cost.FREE);

        /** A free price: the drain succeeded and there is nothing to give back. */
        public static final Receipt FREE = new Receipt(true, Cost.FREE);
    }

    private final CurrencyEngine currencies;
    private final ItemWallet items;
    private final Consumer<String> warn;

    private CostEngine(@Nonnull Builder b) {
        this.currencies = b.currencies;
        this.items = b.items;
        this.warn = b.warn;
    }

    /** The currency engine every currency component is charged through. */
    @Nonnull
    public CurrencyEngine currencies() {
        return currencies;
    }

    // ==================== Check ====================

    /**
     * Can {@code subject} pay {@code cost} right now? An {@code All} price needs every component
     * and reports the first it falls short on; an {@code Any} price needs ONE component in full and
     * reports the first component when none of them can be met. A free price always passes.
     */
    @Nonnull
    public Affordability check(@Nonnull Subject subject, @Nonnull Cost cost) {
        if (cost.isFree()) {
            return Affordability.OK;
        }
        if (cost.combine() == Cost.Combine.ANY) {
            for (Map.Entry<String, Long> owed : cost.currencies().entrySet()) {
                if (currencies.balance(subject, owed.getKey()) >= owed.getValue()) {
                    return Affordability.OK;
                }
            }
            for (ItemCost item : cost.items()) {
                if (items.count(subject, item.item()) >= item.count()) {
                    return Affordability.OK;
                }
            }
            String firstCurrency = cost.primaryCurrencyId();
            if (firstCurrency != null) {
                return Affordability.shortCurrency(firstCurrency);
            }
            return Affordability.shortItem(cost.items().get(0).item());
        }
        for (Map.Entry<String, Long> owed : cost.currencies().entrySet()) {
            if (currencies.balance(subject, owed.getKey()) < owed.getValue()) {
                return Affordability.shortCurrency(owed.getKey());
            }
        }
        for (ItemCost item : cost.items()) {
            if (items.count(subject, item.item()) < item.count()) {
                return Affordability.shortItem(item.item());
            }
        }
        return Affordability.OK;
    }

    /** {@link #check} as a boolean, for a caller with nothing to say about the shortfall. */
    public boolean canPay(@Nonnull Subject subject, @Nonnull Cost cost) {
        return check(subject, cost).ok();
    }

    // ==================== Drain ====================

    /**
     * Charge {@code cost} to {@code subject} and answer what was taken. A refused drain leaves
     * every balance and every stack exactly where it was.
     */
    @Nonnull
    public Receipt drain(@Nonnull Subject subject, @Nonnull Cost cost) {
        if (cost.isFree()) {
            return Receipt.FREE;
        }
        if (!check(subject, cost).ok()) {
            return Receipt.FAILED;
        }
        return cost.combine() == Cost.Combine.ANY ? drainAny(subject, cost) : drainAll(subject, cost);
    }

    /** Charge exactly ONE component: the first the payer can afford, in authored order. */
    @Nonnull
    private Receipt drainAny(@Nonnull Subject subject, @Nonnull Cost cost) {
        for (Map.Entry<String, Long> owed : cost.currencies().entrySet()) {
            if (currencies.balance(subject, owed.getKey()) < owed.getValue()) {
                continue;
            }
            if (!currencies.debit(subject, owed.getKey(), owed.getValue())) {
                return Receipt.FAILED;
            }
            return new Receipt(true, Cost.single(owed.getKey(), owed.getValue()));
        }
        for (ItemCost item : cost.items()) {
            if (items.count(subject, item.item()) < item.count()) {
                continue;
            }
            if (!items.take(subject, item.item(), item.count())) {
                return Receipt.FAILED;
            }
            return new Receipt(true, Cost.singleItem(item.item(), item.count()));
        }
        return Receipt.FAILED;
    }

    /** Charge every component, or none: items verified, currencies rolled back, items removed last. */
    @Nonnull
    private Receipt drainAll(@Nonnull Subject subject, @Nonnull Cost cost) {
        List<ItemCost> owedItems = cost.items();
        if (!hasEveryItem(subject, owedItems)) {
            return Receipt.FAILED;
        }
        if (!takeAllOrRollback(subject, cost.currencies())) {
            return Receipt.FAILED;
        }
        if (!takeEveryItem(subject, owedItems)) {
            // The items went somewhere between the check and now; put the currencies back so the
            // payer is not left short with nothing to show for it.
            cost.currencies().forEach((id, amount) -> currencies.refund(subject, id, amount));
            warn.accept("[cost] items vanished after the currencies were taken, so the price was refunded");
            return Receipt.FAILED;
        }
        return new Receipt(true, cost);
    }

    /**
     * Take every currency amount, putting back each one already taken the moment one fails, so a
     * multi-currency price can never half-charge. Public because a consumer paying an All price its
     * own way needs the same guarantee rather than its own version of it.
     */
    public boolean takeAllOrRollback(@Nonnull Subject subject, @Nonnull Map<String, Long> owed) {
        List<Map.Entry<String, Long>> taken = new ArrayList<>();
        for (Map.Entry<String, Long> entry : owed.entrySet()) {
            if (currencies.debit(subject, entry.getKey(), entry.getValue())) {
                taken.add(entry);
                continue;
            }
            for (Map.Entry<String, Long> back : taken) {
                currencies.refund(subject, back.getKey(), back.getValue());
            }
            warn.accept("[cost] '" + entry.getKey() + "' could not be taken, so the whole price rolled back");
            return false;
        }
        return true;
    }

    private boolean hasEveryItem(@Nonnull Subject subject, @Nonnull List<ItemCost> owed) {
        for (ItemCost item : owed) {
            if (items.count(subject, item.item()) < item.count()) {
                return false;
            }
        }
        return true;
    }

    /** Remove every item, putting back the ones already removed if a later one refuses. */
    private boolean takeEveryItem(@Nonnull Subject subject, @Nonnull List<ItemCost> owed) {
        List<ItemCost> taken = new ArrayList<>();
        for (ItemCost item : owed) {
            if (items.take(subject, item.item(), item.count())) {
                taken.add(item);
                continue;
            }
            for (ItemCost back : taken) {
                items.give(subject, back.item(), back.count());
            }
            return false;
        }
        return true;
    }

    // ==================== Refund ====================

    /**
     * Give back everything on {@code paid}, which is a {@link Receipt}'s own cost and never the
     * price that produced it. A free receipt refunds nothing.
     */
    public void refund(@Nonnull Subject subject, @Nonnull Cost paid) {
        if (paid.isFree()) {
            return;
        }
        paid.currencies().forEach((id, amount) -> currencies.refund(subject, id, amount));
        for (ItemCost item : paid.items()) {
            items.give(subject, item.item(), item.count());
        }
    }

    /** {@link #refund} straight off a receipt, which is what a compensating caller has in hand. */
    public void refund(@Nonnull Subject subject, @Nonnull Receipt receipt) {
        if (receipt.ok()) {
            refund(subject, receipt.paid());
        }
    }

    /** A price description of what is owed, for a preview that shows several components at once. */
    @Nonnull
    public Map<String, Long> shortfall(@Nonnull Subject subject, @Nonnull Cost cost) {
        Map<String, Long> missing = new LinkedHashMap<>();
        cost.currencies().forEach((id, amount) -> {
            long held = currencies.balance(subject, id);
            if (held < amount) {
                missing.put(id, amount - held);
            }
        });
        for (ItemCost item : cost.items()) {
            long held = items.count(subject, item.item());
            if (held < item.count()) {
                missing.put(item.item(), item.count() - held);
            }
        }
        return missing;
    }

    @Nonnull
    public static Builder builder(@Nonnull CurrencyEngine currencies) {
        return new Builder(currencies);
    }

    /** Assembles a {@link CostEngine}. */
    public static final class Builder {

        private final CurrencyEngine currencies;
        private ItemWallet items = NativeItemWallet.INSTANCE;
        private Consumer<String> warn = SafeLog::warn;

        private Builder(@Nonnull CurrencyEngine currencies) {
            this.currencies = currencies;
        }

        /** Where raw item components are counted and moved. Defaults to the real inventory. */
        @Nonnull
        public Builder items(@Nonnull ItemWallet items) {
            this.items = items;
            return this;
        }

        /** Where a rollback and a vanished item are reported. */
        @Nonnull
        public Builder warn(@Nonnull Consumer<String> warn) {
            this.warn = warn;
            return this;
        }

        @Nonnull
        public CostEngine build() {
            return new CostEngine(this);
        }
    }
}
