package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.loot.StochasticCount;
import com.ziggfreed.common.util.SafeLog;

/**
 * The {@code Moment_Item} reward kind and the collector it pays into: "another of the stack this
 * pass is about". {@code {"Kind": "Moment_Item", "Params": {"Count": 0.5}}}.
 *
 * <p>A pass that rolls loot about one stack (an item a player just picked up, say) builds one
 * {@code MomentItems} around that stack, layers it on the subject it grants through
 * ({@code subject.withFacets(momentItems)}), rolls, and then asks {@link #copies} for what the
 * pass's {@code Moment_Item} rewards added up to. It is a framework kind ({@link CollectingRewardKind}):
 * registered once into {@link RewardKinds#shared()}, it finds the pass's collector on the subject at
 * any depth, a nested table included, and anywhere no pass carries one it pays nothing, loudly.
 *
 * <table>
 *   <caption>Parameters</caption>
 *   <tr><th>Key</th><th>Meaning</th></tr>
 *   <tr><td>{@code Count}</td><td>How many whole copies of the moment's stack, fractional allowed
 *       ({@code 0.5} is one copy half the time). Default 1.</td></tr>
 * </table>
 *
 * <h2>Sum, then resolve once</h2>
 *
 * <p>Every reward adds its {@code Count} to the pass's tally, and {@link #copies} resolves the sum
 * ONCE through {@link StochasticCount#resolve}: two rewards of {@code 0.5} in one pass hand over one
 * copy every time, never zero or two. A whole tally consumes no draw.
 *
 * <h2>The engine's own copy</h2>
 *
 * <p>This is the family's ONE place that copies a moment's stack. Each copy is a separate
 * {@link ItemStack#cleanCopy()}: the engine's copy, keeping the item, its quantity, durability,
 * quality and metadata. Never {@code withQuantity} (it answers the same instance for an unchanged
 * count) and never one oversized stack, so a stack of a single unstackable item copies as that many
 * separate items. Hand the copies over through {@link GroundSpillSinks#spill}, which keeps each stack
 * whole; the preset's item sink rebuilds a bare stack from an id and would drop what the copy kept.
 *
 * <h2>Fails loud</h2>
 *
 * <p>A {@code Count} of zero, below zero, not finite or unreadable THROWS, so the payout layer counts
 * the reward lost, warns, and records the failure against the kind's owner: a quiet return would
 * report as paid a reward that authored nothing. A resolved total above {@link #MAX_COPIES} is
 * clamped to it, with one warning per server run; so is a sum too large to count. Wherever a
 * {@code Count} is authored, a table included, {@code LootableValidator} warns about one that cannot
 * pay ({@link #parseCount}, {@link #isPayable}) and one above the ceiling.
 *
 * <p>World-thread only, like the pass it collects for.
 */
public final class MomentItems {

    /** The kind id content writes. Unprefixed: a framework kind. */
    public static final String KIND = "Moment_Item";

    /** The one parameter the kind reads, as the lower-cased key {@link RewardSpec} stores. */
    public static final String P_COUNT = "count";

    /**
     * The most copies one pass hands over. A bonus copy of a moment's stack is one or two in any sane
     * table; a total this high is a runaway sum or a typo, and every copy is its own stack (its own
     * ground entity when the bag is full), so the clamp keeps one pass from flooding the world.
     */
    public static final int MAX_COPIES = 64;

    /** The one handler, so registering twice keeps the existing entry and its failure history. */
    private static final CollectingRewardKind<MomentItems> HANDLER =
            CollectingRewardKind.of(KIND, MomentItems.class, (collector, spec) -> collector.add(countOf(spec)));

    /** The clamp warns once per server run, not once per pass. */
    private static final AtomicBoolean CLAMP_WARNED = new AtomicBoolean();

    @Nonnull
    private final ItemStack moment;

    private double tally;

    /**
     * A collector for one pass about {@code moment}, the stack exactly as the engine handed it over.
     *
     * @throws IllegalArgumentException when {@code moment} is null or empty
     */
    public MomentItems(@Nullable ItemStack moment) {
        if (moment == null || moment.getItemId() == null || moment.isEmpty()) {
            throw new IllegalArgumentException("a " + KIND + " collector needs the stack its moment is about");
        }
        this.moment = moment;
    }

    /** Register the kind into {@code kinds}, attributed to the framework. Idempotent. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, LootRewardKinds.OWNER, HANDLER);
    }

    /** Every parameter key this kind reads, for a validator that wants to warn about a typo. */
    @Nonnull
    public static Map<String, List<String>> parameterKeys() {
        return Map.of(KIND, List.of(P_COUNT));
    }

    /** The copies collected so far and not yet resolved, fractional. */
    public double tally() {
        return tally;
    }

    /**
     * Resolve the tally once into that many separate copies of the moment's stack, then zero it.
     * Consults {@code sample} at most once, and only when the tally has a fraction.
     *
     * @param sample a uniform {@code [0,1)} source
     */
    @Nonnull
    public List<ItemStack> copies(@Nonnull DoubleSupplier sample) {
        return copies(sample, SafeLog::warn);
    }

    /** As {@link #copies(DoubleSupplier)}, reporting a clamp to {@code warn}. */
    @Nonnull
    List<ItemStack> copies(@Nonnull DoubleSupplier sample, @Nonnull Consumer<String> warn) {
        double amount = tally;
        tally = 0.0;
        // A sum that overflowed the double is a runaway total like any other: clamp it, never
        // let the fractional resolve read it as nothing.
        boolean overflowed = !Double.isFinite(amount);
        int count = overflowed ? Integer.MAX_VALUE : StochasticCount.resolve(amount, sample);
        if (count > MAX_COPIES) {
            if (CLAMP_WARNED.compareAndSet(false, true)) {
                warn.accept("'" + KIND + "' rewards added up to "
                        + (overflowed ? "more copies than can be counted" : count + " copies") + " of '"
                        + moment.getItemId() + "' in one pass; handing over " + MAX_COPIES + ", the most one pass"
                        + " gives. Check the Count of the tables paying it (said once per server run)");
            }
            count = MAX_COPIES;
        }
        List<ItemStack> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(moment.cleanCopy());
        }
        return out;
    }

    private void add(double count) {
        tally += count;
    }

    /**
     * A reward's {@code Count}: 1 when unwritten, else a positive finite number.
     *
     * @throws IllegalStateException for zero, a negative, a non-finite or an unreadable count
     */
    static double countOf(@Nonnull RewardSpec spec) {
        String written = spec.param(P_COUNT);
        if (written == null) {
            return 1.0;
        }
        double count = parseCount(written);
        if (Double.isNaN(count)) {
            throw new IllegalStateException("a reward of kind '" + KIND + "' has a 'Count' of '" + written
                    + "', which is not a number, so it hands over nothing");
        }
        if (!isPayable(count)) {
            throw new IllegalStateException("a reward of kind '" + KIND + "' has a 'Count' of " + written
                    + ", so it hands over nothing; write a positive number of copies, or remove it for one");
        }
        return count;
    }

    /**
     * A written {@code Count} read as the kind reads it at payout: the number, or NaN when it is not
     * one. For a validator, so the authoring check and the payout read one rule.
     */
    public static double parseCount(@Nonnull String written) {
        try {
            return Double.parseDouble(written.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Whether a {@code Count} pays at all: a finite number above zero. Anything else fails at payout. */
    public static boolean isPayable(double count) {
        return Double.isFinite(count) && count > 0.0;
    }

    /** Test seam: let the next clamp warn again. */
    static void resetClampWarningForTesting() {
        CLAMP_WARNED.set(false);
    }
}
