package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.commerce.fold.CommerceFold;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.ItemCost;

/**
 * What a pick's takes cost in wallets and items, pure: the price of the next take of a pick whose {@code Cost.Price}
 * grows ({@code Cost.Scaling}, zc-commerce's one growth rule, {@link Cost#scaled}), what a run of takes cost in all,
 * and the share of it a kind respec gives back ({@code TrackPickRespecAsset}'s {@code Refund}). A pick writing no
 * {@code Scaling} costs its {@code Price} for every take, exactly as before.
 */
public final class PickPrices {

    private PickPrices() {
    }

    /**
     * {@code pick}'s {@code Cost.Price} folded through the library's one price ({@link CommerceFold#cost}), carrying
     * its {@code Cost.Scaling} when it writes one: what {@link #nth} grows. {@link Cost#FREE} for a pick with no price.
     */
    @Nonnull
    public static Cost base(@Nonnull TrackPickAsset pick) {
        return base(pick.price(), pick.priceScaling(), "the pick " + pick.getId());
    }

    /** {@link #base(TrackPickAsset)} of an authored price and growth. */
    @Nonnull
    static Cost base(@Nullable CostAsset price, @Nullable TrackPickAsset.Scaling scaling, @Nonnull String source) {
        if (price == null) {
            return Cost.FREE;
        }
        Cost folded = CommerceFold.cost(price, source);
        return scaling == null || folded.isFree() ? folded
                : Cost.of(folded.combine(), folded.currencies(), folded.items(), scaling.toScaling());
    }

    /**
     * What the take after {@code taken} takes owned costs, out of {@code base} ({@link #base}): itself while nothing
     * grows it or none is owned yet, else every wallet amount and item count grown for {@code taken} takes.
     */
    @Nonnull
    public static Cost nth(@Nonnull Cost base, int taken) {
        return base.scaled(Math.max(0, taken));
    }

    /**
     * What {@code takes} takes of a pick priced {@code base} cost in all, each at the price it had as that take
     * ({@link #nth} of 0, 1, ...): every wallet amount and item count summed. A price that charges one of its parts
     * ({@code Combine Any}) counts its first part for each take, the one a player who can pay any of them pays.
     */
    @Nonnull
    public static Cost paidFor(@Nonnull Cost base, int takes) {
        if (takes <= 0 || base.isFree()) {
            return Cost.FREE;
        }
        Map<String, Long> currencies = new LinkedHashMap<>();
        Map<String, Long> items = new LinkedHashMap<>();
        for (int i = 0; i < takes; i++) {
            add(charged(nth(base, i)), currencies, items);
        }
        return of(currencies, items);
    }

    /** {@code a} and {@code b} together: every wallet amount and item count summed, every part charged. */
    @Nonnull
    public static Cost plus(@Nonnull Cost a, @Nonnull Cost b) {
        if (b.isFree()) {
            return a;
        }
        if (a.isFree()) {
            return b;
        }
        Map<String, Long> currencies = new LinkedHashMap<>();
        Map<String, Long> items = new LinkedHashMap<>();
        add(a, currencies, items);
        add(b, currencies, items);
        return of(currencies, items);
    }

    /**
     * The share of {@code total} given back: each wallet amount times {@code currencyShare} of its currency id
     * (lower-cased, as a folded price holds it), each item count times {@code itemShare}, each share held between 0
     * and 1 and each product rounded down, so nothing comes back past what was paid.
     */
    @Nonnull
    public static Cost shared(@Nonnull Cost total, @Nonnull ToDoubleFunction<String> currencyShare,
            double itemShare) {
        if (total.isFree()) {
            return Cost.FREE;
        }
        Map<String, Long> currencies = new LinkedHashMap<>();
        total.currencies().forEach((id, amount) -> {
            long back = share(amount, currencyShare.applyAsDouble(id));
            if (back > 0L) {
                currencies.put(id, back);
            }
        });
        Map<String, Long> items = new LinkedHashMap<>();
        for (ItemCost item : total.items()) {
            long back = share(item.count(), itemShare);
            if (back > 0L) {
                items.merge(item.item(), back, Long::sum);
            }
        }
        return of(currencies, items);
    }

    /** {@code amount} times {@code share} held between 0 and 1, rounded down. */
    static long share(long amount, double share) {
        if (amount <= 0L || !(share > 0.0)) {
            return 0L;
        }
        if (share >= 1.0) {
            return amount;
        }
        return (long) Math.floor(amount * share);
    }

    /** What a take priced {@code price} charges a player who can pay every part: all of it, or its first part. */
    @Nonnull
    private static Cost charged(@Nonnull Cost price) {
        if (price.combine() != Cost.Combine.ANY || price.componentCount() <= 1) {
            return price;
        }
        String currency = price.primaryCurrencyId();
        if (currency != null) {
            return Cost.single(currency, price.amountOf(currency));
        }
        ItemCost first = price.items().get(0);
        return Cost.singleItem(first.item(), first.count());
    }

    private static void add(@Nonnull Cost cost, @Nonnull Map<String, Long> currencies,
            @Nonnull Map<String, Long> items) {
        cost.currencies().forEach((id, amount) -> currencies.merge(id, amount, PickPrices::sum));
        for (ItemCost item : cost.items()) {
            items.merge(item.item(), (long) item.count(), PickPrices::sum);
        }
    }

    private static long sum(long a, long b) {
        long out = a + b;
        return ((a ^ out) & (b ^ out)) < 0 ? Long.MAX_VALUE : out;
    }

    @Nonnull
    private static Cost of(@Nonnull Map<String, Long> currencies, @Nonnull Map<String, Long> items) {
        List<ItemCost> owed = new ArrayList<>();
        items.forEach((id, count) -> owed.add(ItemCost.of(id, (int) Math.min(Integer.MAX_VALUE, count))));
        return Cost.of(Cost.Combine.ALL, currencies, owed, null);
    }
}
