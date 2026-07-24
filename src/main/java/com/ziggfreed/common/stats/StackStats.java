package com.ziggfreed.common.stats;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * The ONE generic per-stack stat / enhancement record: a plain {@link BuilderCodec} data blob
 * attached to an {@link ItemStack} via {@link ItemStack#withMetadata(KeyedCodec, Object)},
 * persisted natively with the stack (trades, chests, restarts carry it along for free). Mirrors
 * the MMO's {@code item.ItemStatsMeta} shape and API (`read`/`entriesOf`/`stampCountOf`,
 * `merge`, `stampReplacing`/`stampReplacingWithCount`) as the mod-agnostic common equivalent -
 * ANY consumer (an enhancing station, a random-roll system, a standalone stamper) that wants to
 * put real stat entries on a per-stack instance writes THIS record, so cap/budget accounting
 * stays cross-mod-consistent no matter which system stamped first.
 *
 * <p><b>{@link #getEntries()} numeric convention</b> (the ONE convention every writer/reader in
 * this domain shares): a PERCENT-family channel stores WHOLE PERCENT POINTS (a +10% bonus is the
 * value {@code 10.0}, matching the native gear-authoring convention the MMO's own channels use),
 * a FLAT/absolute channel stores its raw number. A reader divides a percent-family value by 100
 * before folding it as a fraction; a flat value is used as-is. This record does not know which
 * channel is which family - that classification lives with whoever owns the channel id.
 *
 * <p>Two stacks carrying different metadata never merge/stack ({@link ItemStack#isStackableWith}
 * compares metadata byte-for-byte). Reads are DEFENSIVE: a bare stack (no {@link #KEY} document)
 * or one this codec cannot decode (a foreign/corrupt document under the same key) both resolve to
 * {@code null} via {@link #read}, never throw.
 */
public final class StackStats {

    /** Top-level metadata document key. */
    public static final String KEY = "ZigStackStats";

    public static final BuilderCodec<StackStats> CODEC = BuilderCodec.builder(StackStats.class, StackStats::new)
            .append(new KeyedCodec<>("Entries", new MapCodec<>(Codec.DOUBLE, LinkedHashMap::new), false),
                    (s, v) -> s.entries = v,
                    s -> s.entries)
            .add()
            .append(new KeyedCodec<>("StampCount", Codec.INTEGER, false),
                    (s, v) -> s.stampCount = v,
                    s -> s.stampCount)
            .add()
            .build();

    public static final KeyedCodec<StackStats> KEYED_CODEC = new KeyedCodec<>(KEY, CODEC);

    @Nullable
    private Map<String, Double> entries;

    /**
     * How many times an enhancing system has successfully stamped THIS exact stack before now.
     * A legacy/bare stack with no {@code StampCount} key reads as 0 via {@link #stampCountOf}.
     */
    @Nullable
    private Integer stampCount;

    public StackStats() {
    }

    public StackStats(@Nullable Map<String, Double> entries) {
        this.entries = entries;
    }

    public StackStats(@Nullable Map<String, Double> entries, @Nullable Integer stampCount) {
        this.entries = entries;
        this.stampCount = stampCount;
    }

    @Nullable
    public Map<String, Double> getEntries() {
        return entries;
    }

    public void setEntries(@Nullable Map<String, Double> entries) {
        this.entries = entries;
    }

    @Nullable
    public Integer getStampCount() {
        return stampCount;
    }

    /**
     * Null-safe, no-throw metadata read: a bare stack (no {@link #KEY} document at all) or one
     * this codec cannot decode (a foreign/corrupt document under the same key) both return
     * {@code null} instead of throwing, so every call site can read blindly without a prior
     * validity check.
     */
    @Nullable
    public static StackStats read(@Nonnull ItemStack stack) {
        try {
            return stack.getFromMetadataOrNull(KEYED_CODEC);
        } catch (Throwable t) {
            warn("read", t);
            return null;
        }
    }

    /**
     * {@link #read}'s {@link #getEntries()}, or {@code null} for a {@code null}, bare, or
     * malformed stack. The one convenience call sites should use instead of re-deriving the
     * fetch + null-guard.
     */
    @Nullable
    public static Map<String, Double> entriesOf(@Nullable ItemStack stack) {
        if (stack == null) {
            return null;
        }
        StackStats meta = read(stack);
        return meta != null ? meta.getEntries() : null;
    }

    /**
     * {@link #getStampCount()}, graceful-defaulted to 0 for a {@code null}, bare, malformed, or
     * never-stamped stack (never a throw) - the convenience read every {@code StampCount}
     * consumer should use instead of re-deriving the fetch + null-guard.
     */
    public static int stampCountOf(@Nullable ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        StackStats meta = read(stack);
        Integer count = meta != null ? meta.getStampCount() : null;
        return count != null && count > 0 ? count : 0;
    }

    /**
     * Stamp {@code entries} onto a COPY of {@code stack}, REPLACING any prior record wholesale
     * ({@link ItemStack#withMetadata} always returns a new, immutable instance - the original
     * {@code stack} is untouched). {@link #getStampCount()} is left untouched (whatever the
     * previous metadata carried, or absent/0 for a first stamp) - a caller that needs to advance
     * it uses {@link #stampReplacingWithCount} instead. The DEFAULT flow for an additive
     * enhancement is {@code stampReplacing(stack, mergeWith(stack, rolled))}.
     */
    @Nonnull
    public static ItemStack stampReplacing(@Nonnull ItemStack stack, @Nonnull Map<String, Double> entries) {
        return stack.withMetadata(KEYED_CODEC, new StackStats(entries, currentStampCountOrNull(stack)));
    }

    /**
     * {@link #stampReplacing} plus an EXPLICIT {@code stampCount} write. The caller is expected
     * to pass {@code stampCountOf(stack) + 1} - this method itself does not increment, it only
     * carries whatever value the caller computed, so a caller with its own counting logic (or one
     * that wants to leave the count unchanged) stays correct.
     */
    @Nonnull
    public static ItemStack stampReplacingWithCount(@Nonnull ItemStack stack, @Nonnull Map<String, Double> entries,
            int stampCount) {
        return stack.withMetadata(KEYED_CODEC, new StackStats(entries, stampCount));
    }

    @Nullable
    private static Integer currentStampCountOrNull(@Nonnull ItemStack stack) {
        StackStats meta = read(stack);
        return meta != null ? meta.getStampCount() : null;
    }

    /**
     * The ADDITIVE combination of {@code stack}'s existing entries and {@code added}: same-statId
     * amounts SUM into one canonical entry, first-seen order is kept. The stack itself is
     * untouched - stamp the result via {@link #stampReplacing}.
     */
    @Nonnull
    public static Map<String, Double> mergeWith(@Nonnull ItemStack stack, @Nonnull Map<String, Double> added) {
        Map<String, Double> existing = entriesOf(stack);
        return merge(existing != null ? existing : Collections.emptyMap(), added);
    }

    /**
     * Pure form of {@link #mergeWith}: canonical summed entries across {@code base} + {@code
     * added}. The ONE summing authority for this record - display composition and cap
     * accounting should sum through this too, so a tooltip / budget can never disagree with what
     * actually got stamped.
     */
    @Nonnull
    public static Map<String, Double> merge(@Nonnull Map<String, Double> base, @Nonnull Map<String, Double> added) {
        Map<String, Double> summed = new LinkedHashMap<>(base);
        for (Map.Entry<String, Double> e : added.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            summed.merge(e.getKey(), e.getValue(), Double::sum);
        }
        return summed;
    }

    private static void warn(@Nonnull String label, @Nonnull Throwable t) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("StackStats." + label + " failed: " + t.getMessage());
        } catch (Throwable ignored) {
            // log manager absent (unit JVM) - swallow, the read stays a graceful null.
        }
    }
}
