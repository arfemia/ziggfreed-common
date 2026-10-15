package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.InventoryUtil;
import com.ziggfreed.common.util.SafeLog;

/**
 * What going into a dungeon costs, and the spend: the items a dungeon's {@code EntryCost.Items} names
 * ({@link DungeonAsset#entryCostItems()}), taken from the bag, storage and hotbar of the player who opens an area
 * for their party ({@link InventoryUtil#spendableSections()}, so nothing worn is ever counted or taken).
 *
 * <p>{@link DungeonEntry} asks {@link #lacking} before it reserves anything, so a player who cannot pay is refused
 * with nothing reserved and nothing spent, and spends through {@link #spend} only once every other check has
 * passed, on the same world-thread step as the check, right before the teleport. A partymate who follows into the
 * area the party already holds is never asked. Every read and write here runs on the player's world thread through
 * the caller's accessor: their store, or the command buffer of the interaction chain they are in.
 */
public final class DungeonEntryCost {

    private static final String LOG = "[dungeon] ";

    /** One item the way in takes, and how many: the id as the dungeon file writes it, the count at least one. */
    public record Item(@Nonnull String itemId, int count) {

        public Item {
            Objects.requireNonNull(itemId, "itemId");
            count = Math.max(1, count);
        }
    }

    private DungeonEntryCost() {
    }

    /**
     * The first item of {@code cost} the player holds fewer of than it asks for (with the count it asks for), or
     * null when they can pay all of it. Free (an empty cost) is always paid.
     */
    @Nullable
    public static Item lacking(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull List<Item> cost) {
        for (Item item : cost) {
            if (!InventoryUtil.has(accessor, ref, item.itemId(), item.count(), InventoryUtil.spendableSections())) {
                return item;
            }
        }
        return null;
    }

    /**
     * Take all of {@code cost} from the player, or none of it: each item is taken in turn, and a shortfall gives
     * back what was already taken. Answers whether all of it was taken.
     */
    public static boolean spend(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull List<Item> cost) {
        if (lacking(accessor, ref, cost) != null) {
            return false;
        }
        List<Item> taken = new ArrayList<>(cost.size());
        for (Item item : cost) {
            int got = InventoryUtil.take(accessor, ref, item.itemId(), item.count(),
                    InventoryUtil.spendableSections());
            if (got < item.count()) {
                if (got > 0) {
                    taken.add(new Item(item.itemId(), got));
                }
                refund(accessor, ref, taken);
                return false;
            }
            taken.add(item);
        }
        return true;
    }

    /**
     * Give {@code cost} back where it was taken from, on the same step that took it, so the room it left is still
     * there; whatever still does not fit is logged.
     */
    public static void refund(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull List<Item> cost) {
        for (Item item : cost) {
            int lost = InventoryUtil.give(accessor, ref, item.itemId(), item.count(),
                    InventoryUtil.spendableSections());
            if (lost > 0) {
                SafeLog.warn(LOG + lost + " of " + item.itemId() + " taken for a dungeon's entry could not be given"
                        + " back: the player's bag had no room");
            }
        }
    }
}
