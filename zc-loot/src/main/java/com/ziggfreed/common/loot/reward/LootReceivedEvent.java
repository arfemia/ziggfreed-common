package com.ziggfreed.common.loot.reward;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A reward payout handed items over to a player: every item its receipt says reached them (a
 * nested table's included), once per OUTERMOST payout, named by what paid it ({@code quest:<id>},
 * {@code achievement:<id>}, a shop's or an encounter's own source id). A reward queued for later, or
 * lost, is not in it, and neither is anything a loot pass dropped on the ground.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired on the paying
 * thread after the payout settles; every collection is a copy.
 */
public final class LootReceivedEvent implements IEvent<Void> {

    /** One item a payout handed over, and how many. */
    public record Received(@Nonnull String itemId, long count) {
    }

    private final UUID playerId;
    private final String sourceId;
    private final List<Received> items;

    public LootReceivedEvent(@Nonnull UUID playerId, @Nonnull String sourceId, @Nonnull List<Received> items) {
        this.playerId = playerId;
        this.sourceId = sourceId == null ? "" : sourceId.trim();
        this.items = List.copyOf(items);
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** What paid the payout, {@code <what paid>:<its id>}; blank when the payout named nothing. */
    @Nonnull
    public String sourceId() {
        return sourceId;
    }

    /** Each item handed over, counts merged, in the order they first landed. */
    @Nonnull
    public List<Received> items() {
        return items;
    }
}
