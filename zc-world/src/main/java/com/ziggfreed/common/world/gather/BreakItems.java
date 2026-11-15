package com.ziggfreed.common.world.gather;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * What a broken block breaks into, as one stack: the item a hand break with {@code held} drops, by the engine's own
 * rules ({@link ToolTier#dropFor}): the block's breaking item, or its own item when its breaking names neither an item
 * nor a drop list, at the engine's drop quantity. A block that breaks only into a drop list (ore and its cobble) has
 * no one item, so it answers null. "Another of the broken block" in a bonus find (zc-objectives' bonus rows hand it to
 * a {@code Moment_Item} reward) is a copy of this stack.
 */
public final class BreakItems {

    private BreakItems() {
    }

    /**
     * The stack {@code block} breaks into for a hand holding {@code held}, or null when it breaks into no one item
     * (a drop list alone, no gathering) or cannot be read. Never throws.
     */
    @Nullable
    public static ItemStack of(@Nullable ItemStack held, @Nullable BlockType block) {
        if (block == null) {
            return null;
        }
        try {
            ToolTier.Drop drop = ToolTier.dropFor(held, block, false);
            if (drop == null) {
                // The swing broke it, so the breaking drop is what fell even where the tool reads say otherwise.
                BlockGathering gathering = block.getGathering();
                BlockBreakingDropType breaking = gathering == null ? null : gathering.getBreaking();
                if (breaking == null) {
                    return null;
                }
                drop = new ToolTier.Drop(breaking.getQuantity(), breaking.getItemId(), breaking.getDropListId());
            }
            String itemId = drop.itemId();
            if (itemId == null || itemId.isBlank()) {
                if (drop.dropListId() != null && !drop.dropListId().isBlank()) {
                    return null;
                }
                itemId = ownItemOf(block);
            }
            if (itemId == null || itemId.isBlank()) {
                return null;
            }
            return new ItemStack(itemId.trim(), Math.max(1, drop.quantity()));
        } catch (Throwable t) {
            return null;
        }
    }

    /** The block's own item id: the item it is defined in, else its own id. */
    @Nullable
    private static String ownItemOf(@Nonnull BlockType block) {
        Item item = block.getItem();
        String id = item == null ? null : item.getId();
        return id != null && !id.isBlank() ? id : block.getId();
    }
}
