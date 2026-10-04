package com.ziggfreed.common.objectives.interaction;

import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.util.SafeLog;

/**
 * The id of the item an interaction chain is about: the item the chain started with, else the stack
 * in hand now, else none.
 *
 * <p>The started-with item comes first because a chain can change what is in hand before a later
 * node runs: a {@code ModifyInventory} that spends the last of a stack leaves the hand empty, and one
 * that swaps a broken item leaves a different item there. Either way the item USED is the one the
 * chain started with. The hand answers only for a chain that started empty-handed.
 */
final class UsedItems {

    private UsedItems() {
    }

    /** The id of the item {@code ctx}'s chain is about, or null when it can name none. World thread. */
    @Nullable
    static String id(@Nullable InteractionContext ctx) {
        if (ctx == null) {
            return null;
        }
        String original = null;
        try {
            Item item = ctx.getOriginalItemType();
            original = item == null ? null : item.getId();
        } catch (Throwable t) {
            SafeLog.fine("[interaction] could not read the item a chain started with", t);
        }
        String held = null;
        try {
            ItemStack stack = InteractionCtx.heldItem(ctx);
            held = stack == null || stack.isEmpty() ? null : stack.getItemId();
        } catch (Throwable t) {
            SafeLog.fine("[interaction] could not read the item in hand", t);
        }
        return pick(original, held);
    }

    /** {@code originalId} when it names something, else {@code heldId}, else null; trimmed. */
    @Nullable
    static String pick(@Nullable String originalId, @Nullable String heldId) {
        if (originalId != null && !originalId.isBlank()) {
            return originalId.trim();
        }
        return heldId == null || heldId.isBlank() ? null : heldId.trim();
    }
}
