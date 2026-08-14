package com.ziggfreed.common.commerce;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * A {@link CommerceStore} that keeps everything in memory for as long as the server runs.
 *
 * <p>Two honest uses and no third. It is what every test drives the engines with, and it is the
 * DEGRADE a server gets before a persistent store is installed: shops work, limits hold, rerolls
 * hold, and all of it is gone at restart. It is not a persistence strategy, and nothing here
 * pretends otherwise.
 *
 * <p>Thread-safe at the map level; the per-subject records are guarded by the store's own monitor
 * where a read and a write have to agree (the reroll cap check above all).
 */
public final class InMemoryCommerceStore implements CommerceStore {

    private final Map<UUID, Wallet> wallets = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Purchases>> purchases = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, PeriodRerolls>> rerolls = new ConcurrentHashMap<>();

    private static final class Wallet {
        final Map<String, Long> balances = new ConcurrentHashMap<>();
        final Map<String, Long> spent = new ConcurrentHashMap<>();
    }

    /** One offer's counts: a lifetime total plus a single day's count and the day it belongs to. */
    private static final class Purchases {
        long epochDay = Long.MIN_VALUE;
        int today;
        int total;
    }

    /** One pool's state for ONE period; a new period replaces the record wholesale. */
    private static final class PeriodRerolls {
        final long period;
        int spent;
        final Map<Integer, String> overrides = new HashMap<>();
        final Map<Integer, Integer> counts = new HashMap<>();
        final Map<Integer, Set<String>> seen = new HashMap<>();

        PeriodRerolls(long period) {
            this.period = period;
        }
    }

    // ==================== Wallet ====================

    @Override
    public long balance(@Nonnull Subject subject, @Nonnull String currencyId) {
        Wallet wallet = wallets.get(subject.id());
        return wallet == null ? 0L : wallet.balances.getOrDefault(currencyId, 0L);
    }

    @Override
    public void setBalance(@Nonnull Subject subject, @Nonnull String currencyId, long value) {
        walletOf(subject).balances.put(currencyId, Math.max(0L, value));
    }

    @Override
    @Nonnull
    public Map<String, Long> balances(@Nonnull Subject subject) {
        Wallet wallet = wallets.get(subject.id());
        return wallet == null ? Collections.emptyMap() : Map.copyOf(wallet.balances);
    }

    @Override
    public void recordSpend(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        if (amount > 0L) {
            walletOf(subject).spent.merge(currencyId, amount, Long::sum);
        }
    }

    @Override
    public long lifetimeSpent(@Nonnull Subject subject, @Nonnull String currencyId) {
        Wallet wallet = wallets.get(subject.id());
        return wallet == null ? 0L : wallet.spent.getOrDefault(currencyId, 0L);
    }

    @Override
    public void refundSpend(@Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        if (amount <= 0L) {
            return;
        }
        walletOf(subject).spent.computeIfPresent(currencyId,
                (id, spent) -> spent <= amount ? null : spent - amount);
    }

    @Nonnull
    private Wallet walletOf(@Nonnull Subject subject) {
        return wallets.computeIfAbsent(subject.id(), id -> new Wallet());
    }

    // ==================== Purchases ====================

    @Override
    public int purchasesToday(@Nonnull Subject subject, @Nonnull String offerId, long epochDay) {
        Purchases record = purchaseRecord(subject, offerId, false);
        return (record == null || record.epochDay != epochDay) ? 0 : record.today;
    }

    @Override
    public int purchasesTotal(@Nonnull Subject subject, @Nonnull String offerId) {
        Purchases record = purchaseRecord(subject, offerId, false);
        return record == null ? 0 : record.total;
    }

    @Override
    public synchronized void recordPurchase(@Nonnull Subject subject, @Nonnull String offerId, long epochDay) {
        Purchases record = purchaseRecord(subject, offerId, true);
        if (record == null) {
            return;
        }
        if (record.epochDay != epochDay) {
            record.epochDay = epochDay;
            record.today = 0;
        }
        record.today++;
        record.total++;
    }

    @Nullable
    private Purchases purchaseRecord(@Nonnull Subject subject, @Nonnull String offerId, boolean create) {
        Map<String, Purchases> byOffer = create
                ? purchases.computeIfAbsent(subject.id(), id -> new ConcurrentHashMap<>())
                : purchases.get(subject.id());
        if (byOffer == null) {
            return null;
        }
        return create ? byOffer.computeIfAbsent(offerId, id -> new Purchases()) : byOffer.get(offerId);
    }

    // ==================== Rerolls ====================

    @Override
    @Nonnull
    public synchronized Map<Integer, String> rerollOverrides(@Nonnull Subject subject,
            @Nonnull String poolId, long period) {
        PeriodRerolls state = current(subject, poolId, period);
        return state == null ? Collections.emptyMap() : new HashMap<>(state.overrides);
    }

    @Override
    public synchronized int rerollsSpent(@Nonnull Subject subject, @Nonnull String poolId, long period) {
        PeriodRerolls state = current(subject, poolId, period);
        return state == null ? 0 : state.spent;
    }

    @Override
    @Nonnull
    public synchronized Set<String> rerollSeenAt(@Nonnull Subject subject, @Nonnull String poolId,
            long period, int position) {
        PeriodRerolls state = current(subject, poolId, period);
        Set<String> seen = state == null ? null : state.seen.get(position);
        return seen == null ? Collections.emptySet() : new HashSet<>(seen);
    }

    @Override
    public synchronized int rerollNextCount(@Nonnull Subject subject, @Nonnull String poolId,
            long period, int position) {
        PeriodRerolls state = current(subject, poolId, period);
        return (state == null ? 0 : state.counts.getOrDefault(position, 0)) + 1;
    }

    @Override
    public synchronized boolean commitReroll(@Nonnull Subject subject, @Nonnull String poolId,
            long period, int maxPerPeriod, int position, @Nullable String replacedId,
            @Nonnull String newId) {
        PeriodRerolls state = getOrCreate(subject, poolId, period);
        if (maxPerPeriod > 0 && state.spent >= maxPerPeriod) {
            return false;
        }
        state.overrides.put(position, newId);
        state.counts.merge(position, 1, Integer::sum);
        Set<String> seen = state.seen.computeIfAbsent(position, k -> new HashSet<>());
        if (replacedId != null && !replacedId.isEmpty()) {
            seen.add(replacedId);
        }
        seen.add(newId);
        state.spent++;
        return true;
    }

    /** The live record for exactly this period, or null. No creation, no mutation. */
    @Nullable
    private PeriodRerolls current(@Nonnull Subject subject, @Nonnull String poolId, long period) {
        Map<String, PeriodRerolls> byPool = rerolls.get(subject.id());
        if (byPool == null) {
            return null;
        }
        PeriodRerolls state = byPool.get(poolId);
        return (state != null && state.period == period) ? state : null;
    }

    @Nonnull
    private PeriodRerolls getOrCreate(@Nonnull Subject subject, @Nonnull String poolId, long period) {
        Map<String, PeriodRerolls> byPool =
                rerolls.computeIfAbsent(subject.id(), id -> new ConcurrentHashMap<>());
        PeriodRerolls state = byPool.get(poolId);
        if (state == null || state.period != period) {
            state = new PeriodRerolls(period);
            byPool.put(poolId, state);
        }
        return state;
    }

    /** Forget everything about {@code subject}, for a test that wants a clean slate. */
    public void clear(@Nonnull Subject subject) {
        wallets.remove(subject.id());
        purchases.remove(subject.id());
        rerolls.remove(subject.id());
    }
}
