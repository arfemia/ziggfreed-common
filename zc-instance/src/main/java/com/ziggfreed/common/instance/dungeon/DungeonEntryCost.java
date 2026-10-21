package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.InventoryUtil;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * What going into a dungeon costs, and the spend: the items a dungeon's {@code EntryCost.Items} names
 * ({@link DungeonAsset#entryCostItems()}), taken from the bag, storage and hotbar of the player who opens an area
 * for their party ({@link InventoryUtil#spendableSections()}, so nothing worn is ever counted or taken).
 *
 * <p>A key ({@code EntryCost.Keys}, {@link DungeonAsset#entryCostKeys()}) waives the items: a player holding any one
 * of them in the same sections ({@link #heldKey}) opens the area, keeps the key and spends nothing, even holding the
 * items too. {@link DungeonEntry} asks for a key first, at both of its looks.
 *
 * <p>{@link DungeonEntry} asks {@link #lacking} before it reserves anything, so a player who cannot pay is refused
 * with nothing reserved and nothing spent, and spends through {@link #spend} only once every other check has
 * passed, on the same world-thread step as the check, right before the teleport. A partymate who follows into the
 * area the party already holds is never asked. Every read and take here runs on the player's world thread through
 * the caller's accessor: their store, or the command buffer of the interaction chain they are in.
 *
 * <p><b>A refund lands the way every item handed to a player lands, and a full bag never eats it.</b> What
 * {@link #refund} gives back goes through {@link LootRewardKinds#handToPlayer}: where the player's own pickup settings
 * route it, with the game's own pickup notice and sound for what went in, and whatever the bag cannot take to the
 * server's overflow policy (by default the ground at their feet). That delivery reads the same bag through the
 * player's own store, on the same world thread.
 */
public final class DungeonEntryCost {

    private static final String LOG = "[dungeon] ";

    /** What a refund is called in the line the delivery leaves when a pile it could not land is lost. */
    private static final String REFUND_SOURCE = "dungeon entry refund";

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
     * The first of {@code keys}, in the order given, the player holds at least one of in their bag, storage or hotbar,
     * or null when they hold none (or none is given). A key held pays the whole cost without being spent.
     */
    @Nullable
    public static String heldKey(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull List<String> keys) {
        for (String key : keys) {
            if (InventoryUtil.has(accessor, ref, key, 1, InventoryUtil.spendableSections())) {
                return key;
            }
        }
        return null;
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
     * Give {@code cost} back on the same step that took it, through the library's one delivery for items handed
     * straight to a player ({@link LootRewardKinds#handToPlayer}): the bag first, with the game's pickup notice, and
     * whatever the bag still cannot take to the overflow policy as one pile, by default at the player's feet. Only a
     * pile the policy could not land is lost, and the delivery warns about it by name; so does a stack that could not
     * be built, or a player no longer there to give it to.
     */
    public static void refund(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull List<Item> cost) {
        if (cost.isEmpty()) {
            return;
        }
        Subject subject = subjectOf(accessor, ref);
        if (subject == null) {
            SafeLog.warn(LOG + cost + " taken for a dungeon's entry could not be handed back: the player is gone");
            return;
        }
        List<ItemStack> stacks = new ArrayList<>(cost.size());
        for (Item item : cost) {
            try {
                stacks.add(new ItemStack(item.itemId(), item.count()));
            } catch (Throwable t) {
                SafeLog.warn(LOG + item.count() + " x " + item.itemId() + " taken for a dungeon's entry could not be"
                        + " handed back: " + t.getMessage());
            }
        }
        if (!stacks.isEmpty()) {
            LootRewardKinds.handToPlayer(subject, REFUND_SOURCE, SafeLog::warn).build().spill(stacks);
        }
    }

    /** The player behind {@code ref} as the delivery's subject, read through the caller's accessor, or null. */
    @Nullable
    private static Subject subjectOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return null;
            }
            return Subject.of(accessor.getComponent(ref, PlayerRef.getComponentType()),
                    accessor.getComponent(ref, Player.getComponentType()));
        } catch (Throwable t) {
            return null;
        }
    }
}
