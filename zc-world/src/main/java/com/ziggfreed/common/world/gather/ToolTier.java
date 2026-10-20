package com.ziggfreed.common.world.gather;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.SoftBlockDropType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTool;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemToolSpec;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * What a swing of this held item does to this block, by the engine's own rules (its block hit in {@code
 * BlockHarvestUtils}: the spec pick, the drop pick and the durability rule, all private), mirrored through public
 * reads so a block a mod breaks for a player obeys the tool exactly as a hand break would. A block whose breaking
 * names a gather type breaks for a tool whose spec for that gather type is of at least the block's quality and has
 * power (an empty hand, or an item that is no tool, by the gather type's default spec), never for a weapon or a
 * builder tool, and drops its breaking drop; a soft block breaks for anything but a weapon it refuses, and drops its
 * soft drop; a placed decoration whose block asks for it drops its own item. A soft block never wears the tool. So a
 * shaped break never takes a block the tool could not mine by hand (R625), and drops what the hand break would.
 */
final class ToolTier {

    /** What a hand break drops: the engine's drop quantity, item and drop list (both null for the block's own item). */
    record Drop(int quantity, @Nullable String itemId, @Nullable String dropListId) {
    }

    private ToolTier() {
    }

    /** Whether a hand hit on {@code blockType} wears the held tool at all: never a soft block's. */
    static boolean wears(@Nullable BlockType blockType) {
        BlockGathering gathering = blockType == null ? null : blockType.getGathering();
        return gathering != null && !gathering.isSoft();
    }

    /**
     * What a hand break of {@code blockType} with {@code held} drops, null when the swing could not break it.
     * {@code deco} says the block was placed as a decoration (the engine's own mark), which drops the block's own item
     * where its gathering asks for that.
     */
    @Nullable
    static Drop dropFor(@Nullable ItemStack held, @Nullable BlockType blockType, boolean deco) {
        if (blockType == null) {
            return null;
        }
        try {
            BlockGathering gathering = blockType.getGathering();
            if (gathering == null) {
                return null;
            }
            Item item = held == null || held.isEmpty() ? null : held.getItem();
            Drop drop;
            ItemToolSpec spec = specFor(item, gathering);
            if (spec != null && spec.getPower() != 0.0f) {
                BlockBreakingDropType breaking = gathering.getBreaking();
                drop = new Drop(breaking.getQuantity(), breaking.getItemId(), breaking.getDropListId());
            } else if (gathering.isSoft()) {
                SoftBlockDropType soft = gathering.getSoft();
                if (!soft.isWeaponBreakable() && item != null && item.getWeapon() != null) {
                    return null;
                }
                drop = new Drop(1, soft.getItemId(), soft.getDropListId());
            } else {
                return null;
            }
            if (deco && gathering.shouldUseDefaultDropWhenPlaced()) {
                return new Drop(drop.quantity(), null, null);
            }
            return drop;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The engine's spec pick: the spec governing how {@code item} gathers the block, null when it cannot. */
    @Nullable
    private static ItemToolSpec specFor(@Nullable Item item, @Nonnull BlockGathering gathering) {
        BlockBreakingDropType breaking = gathering.getBreaking();
        String gatherType = breaking == null ? null : breaking.getGatherType();
        if (gatherType == null) {
            return null;
        }
        if (item != null && (item.getWeapon() != null || item.getBuilderTool() != null)) {
            return null;
        }
        int required = breaking.getQuality();
        ItemTool tool = item == null ? null : item.getTool();
        if (tool != null) {
            ItemToolSpec[] specs = tool.getSpecs();
            if (specs != null) {
                for (ItemToolSpec spec : specs) {
                    if (spec != null && Objects.equals(spec.getGatherType(), gatherType)) {
                        return spec.getQuality() < required ? null : spec;
                    }
                }
            }
            return null;
        }
        ItemToolSpec fallback = ItemToolSpec.getAssetMap().getAsset(gatherType);
        return fallback != null && fallback.getQuality() < required ? null : fallback;
    }
}
