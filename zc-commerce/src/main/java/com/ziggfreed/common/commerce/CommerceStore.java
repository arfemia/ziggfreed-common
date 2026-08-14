package com.ziggfreed.common.commerce;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * Everything the commerce engines persist about one subject: a counter-backed wallet, how often
 * each offer has been bought, and which rotating positions a player has re-rolled.
 *
 * <p>ONE seam rather than three, because the state is one domain's and a consumer swapping it in
 * has to swap all of it at once - a wallet in one store and its spend record in another is exactly
 * the split that lets the two disagree. A consumer replaces the whole implementation
 * ({@link CommerceStores}) rather than switching parts of it off, so double state cannot exist.
 *
 * <p><b>An item-backed currency never reaches here.</b> Its balance IS the player's inventory count,
 * so the currency engine reads and writes it through the inventory instead and this store is never
 * asked. Only counter-backed balances live in {@link #balance}.
 *
 * <p><b>A store says what it can hold.</b> {@link #recordsPurchases()} and {@link #recordsRerolls()}
 * are the honest capability probes, the same shape the quest progress store uses: an implementation
 * that cannot keep purchase counts answers false, and the engine reports the authored limits as
 * inert at load rather than letting them quietly not work.
 *
 * <p>Implementations are called from the world thread and may be asked about a subject they have
 * never seen; every read answers a neutral value rather than throwing.
 */
public interface CommerceStore {

    // ==================== The wallet (counter-backed currencies only) ====================

    /** This subject's counter balance for {@code currencyId}, or 0 when it holds none. */
    long balance(@Nonnull Subject subject, @Nonnull String currencyId);

    /** Write this subject's counter balance for {@code currencyId}. The engine clamps before calling. */
    void setBalance(@Nonnull Subject subject, @Nonnull String currencyId, long value);

    /** Every counter balance this subject holds, for a wallet listing. Empty when it holds none. */
    @Nonnull
    default Map<String, Long> balances(@Nonnull Subject subject) {
        return Collections.emptyMap();
    }

    /**
     * Record that {@code amount} of {@code currencyId} was genuinely spent (a drain that succeeded,
     * never a refund). A lifetime tally a consumer's own content may gate on; a store with nowhere
     * to keep it does nothing, which costs the tally and nothing else.
     */
    default void recordSpend(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
    }

    /** How much of {@code currencyId} this subject has spent in its lifetime, or 0. */
    default long lifetimeSpent(@Nonnull Subject subject, @Nonnull String currencyId) {
        return 0L;
    }

    /**
     * Undo part of a lifetime spend, because a refund that left the tally standing would leave a
     * respec or a failed purchase counted forever. Never takes it below zero.
     */
    default void refundSpend(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
    }

    // ==================== Purchase limits ====================

    /**
     * How many times this subject has bought {@code offerId} during the day numbered
     * {@code epochDay}. A store holding a single day's count plus the day it belongs to answers 0
     * once the day has rolled over, which is what makes a daily limit reset with no sweep.
     */
    int purchasesToday(@Nonnull Subject subject, @Nonnull String offerId, long epochDay);

    /** How many times this subject has bought {@code offerId}, ever. */
    int purchasesTotal(@Nonnull Subject subject, @Nonnull String offerId);

    /**
     * Record one purchase of {@code offerId} on the day numbered {@code epochDay}. The engine
     * threads ONE {@code epochDay} through a whole purchase, so a transaction spanning midnight
     * cannot check yesterday's count and record against today's.
     */
    void recordPurchase(@Nonnull Subject subject, @Nonnull String offerId, long epochDay);

    /** True when this store genuinely keeps purchase counts; false leaves authored limits inert. */
    default boolean recordsPurchases() {
        return true;
    }

    // ==================== Rotating-pool rerolls ====================

    /**
     * This subject's position overrides for {@code (poolId, period)}: position to the item id shown
     * there instead of the base draw's. Empty for a period this subject has not re-rolled in, which
     * is what makes a rotation rollover wipe the state with no sweep.
     */
    @Nonnull
    Map<Integer, String> rerollOverrides(@Nonnull Subject subject, @Nonnull String poolId, long period);

    /** How many rerolls this subject has spent in {@code (poolId, period)}. */
    int rerollsSpent(@Nonnull Subject subject, @Nonnull String poolId, long period);

    /**
     * Every id that has already occupied {@code position} this period (the original pick plus each
     * replacement). A reroll excludes them, so a position can never cycle back to something the
     * player has already re-rolled away.
     */
    @Nonnull
    Set<String> rerollSeenAt(@Nonnull Subject subject, @Nonnull String poolId, long period, int position);

    /**
     * The reroll count {@code position} will have AFTER its next reroll, which is what seeds the
     * replacement draw before anything is committed. Stable until {@link #commitReroll}.
     */
    int rerollNextCount(@Nonnull Subject subject, @Nonnull String poolId, long period, int position);

    /**
     * Commit one successful single-position reroll: set the override, bump that position's count,
     * remember both the replaced and the new id at that position, and spend one reroll.
     *
     * <p><b>Cap-checked atomically.</b> Answers false without mutating anything when the period's
     * cap is already reached, so a caller charging a price can charge it around this call and
     * compensate on a false rather than discovering the refusal afterwards.
     */
    boolean commitReroll(@Nonnull Subject subject, @Nonnull String poolId, long period,
            int maxPerPeriod, int position, @Nullable String replacedId, @Nonnull String newId);

    /** True when this store genuinely keeps reroll state; false leaves a paid reroll unrepeatable. */
    default boolean recordsRerolls() {
        return true;
    }

    // ==================== Lifecycle ====================

    /**
     * Push anything held for {@code subject} to durable storage. A no-op for a store whose writes
     * are already durable, and for the in-memory one.
     */
    default void flush(@Nonnull Subject subject) {
    }
}
