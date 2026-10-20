package com.ziggfreed.common.currency;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.InventoryUtil;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The real {@link ItemWallet}: an item-backed balance IS the player's inventory count, read and
 * taken through the shared {@code inventory/InventoryUtil} primitives, and handed over through the
 * library's one delivery for items handed straight to a player.
 *
 * <p>The player comes off the subject's own handle, so nothing here learns a consumer's player
 * representation. A subject with no live player behind it reads 0 and moves nothing, which is the
 * same answer an empty inventory gives and the right one for an offline grant: an item-backed
 * currency has nowhere to go until its owner is standing somewhere.
 *
 * <p><b>A balance is what a player CARRIES, not what they are wearing.</b> Reads and takes are
 * scoped to the backpack, storage and hotbar; armor and utility slots are deliberately out of
 * scope. Counting them would let an offer read as affordable on the strength of a helmet and then
 * take the helmet off the player's body to pay for itself, which is not a price anyone authored.
 *
 * <p><b>A payout lands the way every item handed to a player lands, and a full bag never eats it.</b>
 * A credit, a refund or a price handed back goes through {@link LootRewardKinds#handToPlayer}: where
 * the player's own pickup settings route it, with the game's own pickup notice and sound for what
 * went in, and whatever the bag cannot take to the server's overflow policy (by default the ground
 * at their feet, where picking it up notices it the normal way). {@link #give} counts both parts as
 * landed, so a caller's receipt names the whole hand-over even though the balance, which reads the
 * bag, sees only the part that went in.
 *
 * <p><b>World-thread reads and writes.</b> A take is verified against the same view it removes
 * from, so a short balance costs nothing and nothing is ever found in one place and taken from
 * another. Every call is try-guarded by the primitives underneath, so a missing component or an
 * engine throw degrades to "nothing moved" rather than an exception into a purchase.
 */
public final class NativeItemWallet implements ItemWallet {

    /** The shared instance; it holds no state, so one is all a server needs. */
    public static final NativeItemWallet INSTANCE = new NativeItemWallet();

    /** An item count above what one inventory can hold, so a long balance clamps safely. */
    private static final long MAX_MOVE = Integer.MAX_VALUE;

    /** What a hand-over is called in the line the delivery leaves when a pile it could not land is lost. */
    private static final String SOURCE = "currency";

    public NativeItemWallet() {
    }

    @Override
    public long count(@Nonnull Subject subject, @Nonnull String itemId) {
        Ref<EntityStore> ref = refOf(subject);
        if (ref == null) {
            return 0L;
        }
        return InventoryUtil.count(ref.getStore(), ref, itemId, InventoryUtil.spendableSections());
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
        // spend() is the primitive that refuses rather than removing what it found. Scoped to what
        // the balance above counted, so the two can never disagree about what is there.
        return InventoryUtil.spend(store, ref, itemId, wanted, InventoryUtil.spendableSections());
    }

    /**
     * Hand {@code amount} of {@code itemId} over through the one delivery ({@link #handOver}) and
     * answer how much landed, in the bag and at the player's feet alike. Short only when the overflow
     * policy could not land the rest (none installed, or no feet to drop at), and the delivery warns
     * about that part by name.
     */
    @Override
    public long give(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
        if (amount <= 0L || refOf(subject) == null) {
            return 0L;
        }
        long remaining = amount;
        long landed = 0L;
        // A long balance can exceed one stack's int count, so hand it over in passes and stop the
        // moment a pass could not land everything (nowhere left to put the rest).
        while (remaining > 0L) {
            int chunk = (int) Math.min(MAX_MOVE, remaining);
            int handed = handOver(subject, itemId, chunk);
            landed += handed;
            remaining -= chunk;
            if (handed < chunk) {
                break;
            }
        }
        return Math.max(0L, landed);
    }

    /**
     * Hand {@code count} of {@code itemId} to the subject's player through
     * {@link LootRewardKinds#handToPlayer}: the bag first, with the game's pickup notice, and the rest
     * to the overflow policy as one pile. Answers how many landed in either place; 0 when the stack
     * cannot even be built.
     */
    private static int handOver(@Nonnull Subject subject, @Nonnull String itemId, int count) {
        ItemStack stack;
        try {
            stack = new ItemStack(itemId, count);
        } catch (Throwable t) {
            SafeLog.warn("[currency] " + count + " x " + itemId + " could not be handed over: " + t.getMessage());
            return 0;
        }
        int landed = 0;
        for (int quantity : LootRewardKinds.handToPlayer(subject, SOURCE, SafeLog::warn).build()
                .spill(List.of(stack)).values()) {
            landed += quantity;
        }
        return Math.min(count, Math.max(0, landed));
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
