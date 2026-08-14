package com.ziggfreed.common.currency;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One currency, as far as the engine is concerned: what backs it, what it is worth at most, and how
 * it leaks away.
 *
 * <p><b>The backing is the only structural choice, and callers never see it.</b> An ITEM-backed
 * currency names a Hytale item id: its balance IS the player's inventory count of that item, so it
 * can be traded, stored and dropped, and the engine's native drop-on-death applies. A
 * COUNTER-backed currency names none: its balance is a plain number in the commerce store, with no
 * inventory representation. Every read and write in {@link CurrencyEngine} dispatches on that one
 * question, so no caller ever branches on it.
 *
 * <p>The economy knobs are independently optional numbers rather than a policy: a cap, a
 * loss-on-death fraction, a decay-per-day fraction. Author none and the currency is a permanent,
 * uncapped, monotonic balance, which is what most of them are.
 *
 * <p><b>{@link #meta} carries the consumer's own knobs and the engine never reads them.</b> Whether
 * a currency belongs on somebody's sidebar, converts from their experience, or shows on their own
 * screens is that mod's vocabulary; putting it here as a namespaced bag is what keeps it out of the
 * shared schema. Two mods' knobs cannot collide, because each one reads under its own namespace.
 */
public final class CurrencyDef {

    private final String id;
    @Nullable private final String nameKey;
    @Nullable private final String backingItemId;
    @Nullable private final String iconItemId;
    private final String color;
    private final long cap;
    private final double lossOnDeathPercent;
    private final double decayPerDayPercent;
    private final Map<String, Map<String, String>> meta;

    private CurrencyDef(@Nonnull Builder b) {
        this.id = b.id;
        this.nameKey = blankToNull(b.nameKey);
        this.backingItemId = blankToNull(b.backingItemId);
        this.iconItemId = blankToNull(b.iconItemId);
        this.color = b.color != null ? b.color : "#ffffff";
        this.cap = Math.max(0L, b.cap);
        this.lossOnDeathPercent = clampFraction(b.lossOnDeathPercent);
        this.decayPerDayPercent = clampFraction(b.decayPerDayPercent);
        Map<String, Map<String, String>> copied = new LinkedHashMap<>();
        b.meta.forEach((namespace, knobs) -> copied.put(namespace, Map.copyOf(knobs)));
        this.meta = Collections.unmodifiableMap(copied);
    }

    @Nonnull
    public static Builder builder(@Nonnull String id) {
        return new Builder(id);
    }

    /** The authored id, casing intact. Lookups match it case-insensitively. */
    @Nonnull
    public String id() {
        return id;
    }

    /**
     * The localization key naming this currency, or null. An item-backed currency with none reads
     * its name off the backing item's own native key, which is why this leaf is optional rather
     * than a display string: the engine never holds text a player reads.
     */
    @Nullable
    public String nameKey() {
        return nameKey;
    }

    /** The Hytale item whose inventory count IS this balance, or null when it is counter-backed. */
    @Nullable
    public String backingItemId() {
        return backingItemId;
    }

    /** True when the balance lives in the player's inventory rather than in the commerce store. */
    public boolean isItemBacked() {
        return backingItemId != null;
    }

    /**
     * The item id to draw as this currency's icon: the authored one, else the backing item. Null
     * only for a counter-backed currency that named no icon. The ONE fallback, so a wallet strip, a
     * price chip and a payout toast cannot draw three different things.
     */
    @Nullable
    public String iconItemId() {
        return iconItemId != null ? iconItemId : backingItemId;
    }

    /** The authored tint for this currency's chips, defaulting to white. */
    @Nonnull
    public String color() {
        return color;
    }

    /** The most of this currency a subject may hold; 0 means uncapped. */
    public long cap() {
        return cap;
    }

    /** True when no cap was authored. */
    public boolean isUncapped() {
        return cap <= 0L;
    }

    /** The fraction of the balance lost on death, 0 to 1. Zero means death costs nothing. */
    public double lossOnDeathPercent() {
        return lossOnDeathPercent;
    }

    /** The fraction of the balance lost per offline day, 0 to 1. Zero means it never decays. */
    public double decayPerDayPercent() {
        return decayPerDayPercent;
    }

    /** Every consumer knob, by namespace. The engine reads none of it. */
    @Nonnull
    public Map<String, Map<String, String>> meta() {
        return meta;
    }

    /** One consumer knob, or null when that namespace or that key was not authored. */
    @Nullable
    public String meta(@Nonnull String namespace, @Nonnull String key) {
        Map<String, String> knobs = meta.get(namespace);
        return knobs == null ? null : knobs.get(key);
    }

    /** One consumer knob read as a flag, or {@code fallback} when unauthored or unparseable. */
    public boolean metaFlag(@Nonnull String namespace, @Nonnull String key, boolean fallback) {
        String value = meta(namespace, key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    /** One consumer knob read as a decimal, or {@code fallback} when unauthored or unparseable. */
    public double metaNumber(@Nonnull String namespace, @Nonnull String key, double fallback) {
        String value = meta(namespace, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The case-insensitive form ids are keyed and compared by. */
    @Nonnull
    public static String normalizeId(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static double clampFraction(double v) {
        if (!Double.isFinite(v) || v < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, v);
    }

    @Override
    public String toString() {
        return "CurrencyDef[" + id + (isItemBacked() ? " item=" + backingItemId : " counter") + "]";
    }

    /** Assembles a {@link CurrencyDef}; every knob is optional but the id. */
    public static final class Builder {

        private final String id;
        @Nullable private String nameKey;
        @Nullable private String backingItemId;
        @Nullable private String iconItemId;
        @Nullable private String color;
        private long cap;
        private double lossOnDeathPercent;
        private double decayPerDayPercent;
        private final Map<String, Map<String, String>> meta = new LinkedHashMap<>();

        private Builder(@Nonnull String id) {
            this.id = id.trim();
        }

        @Nonnull
        public Builder nameKey(@Nullable String v) {
            this.nameKey = v;
            return this;
        }

        /** Name a backing item to make this currency item-backed; null keeps it counter-backed. */
        @Nonnull
        public Builder backingItem(@Nullable String v) {
            this.backingItemId = v;
            return this;
        }

        @Nonnull
        public Builder iconItem(@Nullable String v) {
            this.iconItemId = v;
            return this;
        }

        @Nonnull
        public Builder color(@Nullable String v) {
            this.color = v;
            return this;
        }

        @Nonnull
        public Builder cap(long v) {
            this.cap = v;
            return this;
        }

        @Nonnull
        public Builder lossOnDeathPercent(double v) {
            this.lossOnDeathPercent = v;
            return this;
        }

        @Nonnull
        public Builder decayPerDayPercent(double v) {
            this.decayPerDayPercent = v;
            return this;
        }

        /** Add one consumer knob under {@code namespace}. */
        @Nonnull
        public Builder meta(@Nonnull String namespace, @Nonnull String key, @Nonnull String value) {
            meta.computeIfAbsent(namespace, ns -> new LinkedHashMap<>()).put(key, value);
            return this;
        }

        /** Add a whole namespace of consumer knobs at once. */
        @Nonnull
        public Builder meta(@Nonnull String namespace, @Nullable Map<String, String> knobs) {
            if (knobs != null && !knobs.isEmpty()) {
                meta.computeIfAbsent(namespace, ns -> new LinkedHashMap<>()).putAll(knobs);
            }
            return this;
        }

        @Nonnull
        public CurrencyDef build() {
            return new CurrencyDef(this);
        }
    }
}
