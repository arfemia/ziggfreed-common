package com.ziggfreed.common.currency;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * An {@link ItemWallet} backed by a plain map, so every pure part of the currency and cost engines
 * can be driven without an inventory, an entity, or a booted server.
 */
final class FakeItemWallet implements ItemWallet {

    private final Map<String, Long> held = new HashMap<>();

    /** How many of {@code itemId} this wallet starts with. */
    void put(@Nonnull String itemId, long amount) {
        held.put(itemId, amount);
    }

    @Override
    public long count(@Nonnull Subject subject, @Nonnull String itemId) {
        return held.getOrDefault(itemId, 0L);
    }

    @Override
    public boolean take(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
        long current = held.getOrDefault(itemId, 0L);
        if (current < amount) {
            return false;
        }
        held.put(itemId, current - amount);
        return true;
    }

    @Override
    public long give(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
        held.merge(itemId, amount, Long::sum);
        return amount;
    }
}
