package com.ziggfreed.common.commerce;

import java.util.Collections;
import java.util.HashMap;
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

    /**
     * Write the lifetime tally outright rather than adding to it, so a tally moved in from somewhere
     * else lands as the number it was rather than on top of whatever is here. The whole write-it-in
     * surface is absolute for that reason: an import that runs twice has to leave the same state, or
     * it is not an import.
     */
    default void setLifetimeSpent(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
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

    /**
     * Write {@code offerId}'s counts outright, rather than adding one to them.
     *
     * <p>Two callers want this and neither can be served by {@link #recordPurchase}: an admin putting
     * a player's limit back, and a consumer moving state it used to keep itself into this store,
     * where the day's count and the lifetime total are independent numbers that no amount of
     * one-at-a-time recording can reproduce.
     *
     * <p>A store with nowhere to keep counts does nothing, which is the same answer
     * {@link #recordsPurchases()} already gives.
     */
    default void setPurchases(@Nonnull Subject subject, @Nonnull String offerId, long epochDay,
            int today, int total) {
    }

    /** Forget every purchase this subject has made, of everything. */
    default void clearPurchases(@Nonnull Subject subject) {
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

    /**
     * This subject's whole reroll state for {@code (poolId, period)} in one piece, for a surface
     * reading out what is there rather than asking a live reroll's questions one at a time.
     *
     * <p>The default composes it from the per-question reads above, so an implementation gets a
     * correct answer for free and overrides this only where reading it in one pass is cheaper.
     */
    @Nonnull
    default RerollState rerollState(@Nonnull Subject subject, @Nonnull String poolId, long period) {
        Map<Integer, String> overrides = rerollOverrides(subject, poolId, period);
        Map<Integer, Integer> counts = new HashMap<>();
        Map<Integer, Set<String>> seen = new HashMap<>();
        for (Integer position : overrides.keySet()) {
            counts.put(position, Integer.valueOf(
                    Math.max(0, rerollNextCount(subject, poolId, period, position.intValue()) - 1)));
            seen.put(position, rerollSeenAt(subject, poolId, period, position.intValue()));
        }
        return new RerollState(period, rerollsSpent(subject, poolId, period), overrides, counts, seen);
    }

    /**
     * Write {@code poolId}'s reroll state outright, replacing whatever was held for it.
     *
     * <p>The counterpart to {@link #setPurchases}, and wanted by the same two callers:
     * {@link #commitReroll} moves the state one step at a time and cap-checks as it goes, which is
     * exactly right for a player paying for a reroll and no use at all for putting a whole recorded
     * state back. Writing an {@link RerollState#isEmpty() empty} state clears this pool.
     */
    default void setRerolls(@Nonnull Subject subject, @Nonnull String poolId,
            @Nonnull RerollState state) {
    }

    /** Forget every reroll this subject has made, in every pool. */
    default void clearRerolls(@Nonnull Subject subject) {
    }

    // ==================== One-time migrations ====================

    /**
     * Claim the right to run {@code migrationId} for this subject, ONCE and for good.
     *
     * <p>True the first time and false ever after, with the claim recorded before the caller acts -
     * so a consumer moving state it used to keep itself into this store runs the move on a player's
     * first connect and never again, however many times the code path is reached.
     *
     * <p><b>The default is false, and that is the safe answer rather than the lazy one.</b> A store
     * with nowhere to keep the mark cannot say "this has not happened yet" without also saying it
     * again at the next login, and a migration that re-runs every login pays a player twice. So a
     * store that cannot remember refuses the migration outright.
     *
     * @param migrationId a stable id owned by whoever is migrating, namespaced so two consumers
     *                    cannot collide (for example {@code "mmoskilltree:commerce-1.6.0"})
     */
    default boolean claimMigration(@Nonnull Subject subject, @Nonnull String migrationId) {
        return false;
    }

    // ==================== Lifecycle ====================

    /**
     * Push anything held for {@code subject} to durable storage. A no-op for a store whose writes
     * are already durable, and for the in-memory one.
     */
    default void flush(@Nonnull Subject subject) {
    }
}
