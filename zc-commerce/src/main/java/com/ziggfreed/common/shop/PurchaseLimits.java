package com.ziggfreed.common.shop;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How often one buyer may take the same offer.
 *
 * <p>The RUNTIME limits, not the authored shape; the authoring layer folds its own group into one
 * of these.
 *
 * <p>Two independent limits, both optional, and neither implies the other: the daily one resets at
 * the day boundary the engine threads through a purchase, the lifetime one never resets. Set both
 * and both hold, which is how "twice a day, ten times ever" is expressed.
 */
public final class PurchaseLimits {

    /** No limit of any kind. */
    public static final PurchaseLimits NONE = new PurchaseLimits(null, null);

    @Nullable private final Integer daily;
    @Nullable private final Integer total;

    private PurchaseLimits(@Nullable Integer daily, @Nullable Integer total) {
        this.daily = (daily == null || daily <= 0) ? null : daily;
        this.total = (total == null || total <= 0) ? null : total;
    }

    /** Limits of {@code daily} per day and {@code total} ever; null or non-positive means no limit. */
    @Nonnull
    public static PurchaseLimits of(@Nullable Integer daily, @Nullable Integer total) {
        return new PurchaseLimits(daily, total);
    }

    /** How many per day, or null when there is no daily limit. */
    @Nullable
    public Integer daily() {
        return daily;
    }

    /** How many ever, or null when there is no lifetime limit. */
    @Nullable
    public Integer total() {
        return total;
    }

    /** True when neither limit limits anything. */
    public boolean isOpen() {
        return daily == null && total == null;
    }

    /** True when {@code boughtToday} has already reached the daily limit. */
    public boolean dailyReached(int boughtToday) {
        return daily != null && boughtToday >= daily;
    }

    /** True when {@code boughtEver} has already reached the lifetime limit. */
    public boolean totalReached(int boughtEver) {
        return total != null && boughtEver >= total;
    }

    @Override
    public String toString() {
        return isOpen() ? "PurchaseLimits[open]" : "PurchaseLimits[daily=" + daily + ", total=" + total + "]";
    }
}
