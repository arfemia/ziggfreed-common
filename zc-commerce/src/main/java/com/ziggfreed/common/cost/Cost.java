package com.ziggfreed.common.cost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What something costs: currencies, raw items, or both.
 *
 * <p><b>The ONE price value in the library.</b> A shop offer, a reroll, an unlock and anything a
 * consumer prices later are all charged through this, so a second, terser idea of "forty of that
 * token" never grows beside it - and a multi-currency or item-priced version of any of them comes
 * free the day somebody wants one.
 *
 * <p>This is the RUNTIME value. What an author writes is {@code commerce.asset.CostAsset}; the
 * authoring layer folds one of these out of it. Keeping the two apart is what lets the whole price
 * engine be exercised by handing it numbers, with no codec, no asset store and no server.
 *
 * <p>{@link Combine} says how a multi-component price is satisfied, and it is a discriminator over
 * ONE component list rather than a mode: {@link Combine#ALL} charges every component,
 * {@link Combine#ANY} charges exactly one of them, whichever the payer can afford first in order.
 * That is what makes "150 tokens or 4 iron" one price rather than two.
 *
 * <p>Immutable. {@link #scaled} answers a NEW price rather than changing this one, so the authored
 * price and the price somebody is being charged today never get confused.
 */
public final class Cost {

    /** How a multi-component price is satisfied. */
    public enum Combine {
        ALL,
        ANY
    }

    /** The price of something that costs nothing. Draining it is a no-op that succeeds. */
    public static final Cost FREE = new Cost(Combine.ALL, Map.of(), List.of(), null);

    private final Combine combine;
    private final Map<String, Long> currencies;
    private final List<ItemCost> items;
    @Nullable private final CostScaling scaling;

    private Cost(@Nonnull Combine combine, @Nullable Map<String, Long> currencies,
            @Nullable List<ItemCost> items, @Nullable CostScaling scaling) {
        this.combine = combine;
        Map<String, Long> owed = new LinkedHashMap<>();
        if (currencies != null) {
            currencies.forEach((id, amount) -> {
                if (id != null && !id.isBlank() && amount != null && amount > 0L) {
                    owed.put(id.trim(), amount);
                }
            });
        }
        this.currencies = Collections.unmodifiableMap(owed);
        List<ItemCost> owedItems = new ArrayList<>();
        if (items != null) {
            for (ItemCost item : items) {
                if (item != null && !item.isBlank()) {
                    owedItems.add(item);
                }
            }
        }
        this.items = Collections.unmodifiableList(owedItems);
        this.scaling = scaling;
    }

    /** A price assembled from its parts. Blank and non-positive components are dropped. */
    @Nonnull
    public static Cost of(@Nonnull Combine combine, @Nullable Map<String, Long> currencies,
            @Nullable List<ItemCost> items, @Nullable CostScaling scaling) {
        return new Cost(combine, currencies, items, scaling);
    }

    /** A single-currency price. Answers {@link #FREE} for a blank id or a non-positive amount. */
    @Nonnull
    public static Cost single(@Nullable String currencyId, long amount) {
        if (currencyId == null || currencyId.isBlank() || amount <= 0L) {
            return FREE;
        }
        return new Cost(Combine.ALL, Map.of(currencyId.trim(), amount), null, null);
    }

    /** A single-item price. Answers {@link #FREE} for a blank id or a non-positive count. */
    @Nonnull
    public static Cost singleItem(@Nullable String itemId, int count) {
        if (itemId == null || itemId.isBlank() || count <= 0) {
            return FREE;
        }
        return new Cost(Combine.ALL, null, List.of(ItemCost.of(itemId, count)), null);
    }

    /** How this price is satisfied. */
    @Nonnull
    public Combine combine() {
        return combine;
    }

    /** Every currency owed, in order. Empty when this price charges none. */
    @Nonnull
    public Map<String, Long> currencies() {
        return currencies;
    }

    /** Every raw item owed, in order. Empty when this price charges none. */
    @Nonnull
    public List<ItemCost> items() {
        return items;
    }

    /** How this price grows per purchase, or null when it never does. */
    @Nullable
    public CostScaling scaling() {
        return scaling;
    }

    /** True when nothing at all is owed. */
    public boolean isFree() {
        return currencies.isEmpty() && items.isEmpty();
    }

    /** How many separately payable components this price has. */
    public int componentCount() {
        return currencies.size() + items.size();
    }

    /** How much of {@code currencyId} this price owes, or 0. */
    public long amountOf(@Nonnull String currencyId) {
        return currencies.getOrDefault(currencyId, 0L);
    }

    /** The first currency charged, in order, or null for an item-only or free price. */
    @Nullable
    public String primaryCurrencyId() {
        return currencies.isEmpty() ? null : currencies.keySet().iterator().next();
    }

    /**
     * This price after {@code priorPurchases}, with every currency amount and item count grown per
     * {@link #scaling()}. Answers {@code this} when nothing scales or nothing has been bought yet,
     * so the common case allocates nothing.
     *
     * <p>Scale ONCE, at the point the price is quoted: the grown price keeps its curve so a preview
     * can still describe it, and scaling an already-grown price would compound it.
     */
    @Nonnull
    public Cost scaled(int priorPurchases) {
        if (scaling == null || priorPurchases <= 0 || isFree()) {
            return this;
        }
        Map<String, Long> grown = new LinkedHashMap<>();
        currencies.forEach((id, amount) -> grown.put(id, CostScaling.scaled(amount, priorPurchases, scaling)));
        List<ItemCost> grownItems = new ArrayList<>();
        for (ItemCost item : items) {
            long count = CostScaling.scaled(item.count(), priorPurchases, scaling);
            grownItems.add(item.withCount((int) Math.min(Integer.MAX_VALUE, count)));
        }
        return new Cost(combine, grown, grownItems, scaling);
    }

    @Override
    public String toString() {
        return isFree() ? "Cost[free]" : "Cost[" + combine + " " + currencies + " " + items + "]";
    }
}
