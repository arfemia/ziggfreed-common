package com.ziggfreed.common.currency;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.InventoryUtil;
import com.ziggfreed.common.subject.Subject;

/**
 * The real {@link ItemWallet}: an item-backed balance IS the player's inventory count, read and
 * moved through the shared {@code inventory/InventoryUtil} primitives.
 *
 * <p>The player comes off the subject's own handle, so nothing here learns a consumer's player
 * representation. A subject with no live player behind it reads 0 and moves nothing, which is the
 * same answer an empty inventory gives and the right one for an offline grant: an item-backed
 * currency has nowhere to go until its owner is standing somewhere.
 *
 * <p><b>Combined-inventory reads and writes, on the world thread.</b> A take is verified against the
 * whole combined view before anything is removed, so a short balance costs nothing. Every call is
 * try-guarded by the primitives underneath, so a missing component or an engine throw degrades to
 * "nothing moved" rather than an exception into a purchase.
 */
public final class NativeItemWallet implements ItemWallet {

    /** The shared instance; it holds no state, so one is all a server needs. */
    public static final NativeItemWallet INSTANCE = new NativeItemWallet();

    /** An item count above what one inventory can hold, so a long balance clamps safely. */
    private static final long MAX_MOVE = Integer.MAX_VALUE;

    public NativeItemWallet() {
    }

    @Override
    public long count(@Nonnull Subject subject, @Nonnull String itemId) {
        Ref<EntityStore> ref = refOf(subject);
        if (ref == null) {
            return 0L;
        }
        return InventoryUtil.count(ref.getStore(), ref, itemId);
    }

    @Override
    public boolean take(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
        if (amount <= 0L) {
            return true;
        }
        Ref<EntityStore> ref = refOf(subject);
        if (ref == null || amount > MAX_MOVE) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        int wanted = (int) amount;
        // Verified first, then removed: a partial take is the one outcome a price cannot undo, and
        // spend() is the primitive that refuses rather than removing what it found.
        return InventoryUtil.spend(store, ref, itemId, wanted);
    }

    @Override
    public long give(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        Ref<EntityStore> ref = refOf(subject);
        if (ref == null) {
            return 0L;
        }
        long remaining = amount;
        long delivered = 0L;
        // A long balance can exceed one give() call's int argument, so hand it over in passes and
        // stop the moment a pass could not place everything (the inventory is full).
        while (remaining > 0L) {
            int chunk = (int) Math.min(MAX_MOVE, remaining);
            int notPlaced = InventoryUtil.give(ref.getStore(), ref, itemId, chunk);
            delivered += chunk - notPlaced;
            remaining -= chunk;
            if (notPlaced > 0) {
                break;
            }
        }
        return Math.max(0L, delivered);
    }

    @Nullable
    private static Ref<EntityStore> refOf(@Nonnull Subject subject) {
        Player player = subject.handleAs(Player.class);
        if (player == null) {
            return null;
        }
        try {
            Ref<EntityStore> ref = player.getReference();
            return (ref != null && ref.isValid()) ? ref : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
