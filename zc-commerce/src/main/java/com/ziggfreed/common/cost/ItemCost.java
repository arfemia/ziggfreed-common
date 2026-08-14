package com.ziggfreed.common.cost;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One raw-item component of a {@link Cost}: a count of a single item id taken out of the payer's
 * inventory.
 *
 * <p>The RUNTIME value, not the authored shape. What an author writes is
 * {@code commerce.asset.CostAsset}'s item entry; this is what the engine charges. Keeping them
 * apart is what lets the price engine be exercised by handing it two numbers, with no codec, no
 * asset store, and no server.
 *
 * <p>A count below one reads as one: a price naming an item at all is asking for at least one of
 * it, and a zero would be a free component that still looks like a price.
 */
public final class ItemCost {

    private final String item;
    private final int count;

    private ItemCost(@Nullable String item, int count) {
        this.item = (item == null) ? "" : item.trim();
        this.count = Math.max(1, count);
    }

    /** A component owing {@code count} of {@code item}. */
    @Nonnull
    public static ItemCost of(@Nullable String item, int count) {
        return new ItemCost(item, count);
    }

    /** The item id, or an empty string when there is none. */
    @Nonnull
    public String item() {
        return item;
    }

    /** How many are owed; at least one. */
    public int count() {
        return count;
    }

    /** True when there is no item id, so this component can never be paid. */
    public boolean isBlank() {
        return item.isEmpty();
    }

    /** A copy of this component owing {@code newCount} instead. */
    @Nonnull
    public ItemCost withCount(int newCount) {
        return of(item, newCount);
    }

    @Override
    public String toString() {
        return "ItemCost[" + item + " x" + count + "]";
    }
}
