package com.ziggfreed.common.usegate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.DisposableItemMetadata;
import com.ziggfreed.common.util.SafeLog;

/**
 * Gear KEPT for a player: a stack stamped with a player's id for a gate {@code Group} passes that group's item-axis
 * use gates ({@code Held}, {@code Worn}) for that player, whatever they ask, and nothing else: another group's gate,
 * a server owner's own gates included, still asks. It is how gear a player already wears or carries keeps working
 * when a gate first appears over it (a content update that adds gear levels, the {@value ItemLevelTags#GROUP}
 * group an item's {@code ZigLevel} tag asks in), while the same item picked up later asks for its level. The stamp
 * rides the stack's own metadata ({@value #METADATA_KEY}, a map from a folded group to the ids of the players it
 * is kept for), so it travels with the item and is saved with the inventory; a stack handed to someone else works
 * for them only if it names them too. It never exempts a target axis: a kept pickaxe still cannot break a block
 * whose own gate refuses.
 *
 * <p>{@link #keepCarried} is the one writer: it stamps, for one player, every stack they carry or wear for each
 * group whose present item-axis gate refuses them right now, so nothing they can already use changes and nothing
 * they could use stops working. A consumer calls it once per player at the moment its own update says gear levels
 * arrive. The key is declared safe to destroy with its item ({@link DisposableItemMetadata}), so a salvage station
 * takes a kept piece like any other. {@link #keeps} and {@link #stamped} are the pure rule over the stamp itself.
 */
public final class UseGateKeeps {

    /** The stack metadata key the keepers live under. */
    public static final String METADATA_KEY = "ZigUseGateKeptFor";

    /** The stamp's shape: a folded group to the ids of the players a stack is kept for in it. */
    private static final MapCodec<String[], Map<String, String[]>> STAMP_CODEC =
            new MapCodec<>(Codec.STRING_ARRAY, HashMap::new);

    /** The sections a carried or worn stack can sit in. */
    private static final int[] SECTIONS = {
        InventoryComponent.HOTBAR_SECTION_ID,
        InventoryComponent.STORAGE_SECTION_ID,
        InventoryComponent.ARMOR_SECTION_ID,
        InventoryComponent.UTILITY_SECTION_ID,
        InventoryComponent.TOOLS_SECTION_ID,
        InventoryComponent.BACKPACK_SECTION_ID,
    };

    private UseGateKeeps() {
    }

    /** Declare the stamp safe to destroy with its item. At setup. */
    public static void declareDisposable() {
        DisposableItemMetadata.declare(METADATA_KEY);
    }

    /**
     * Is {@code stack} kept for {@code playerId} in {@code group} (as authored or folded)? False for an empty
     * stack, an unknown player or no stamp for that group.
     */
    public static boolean keptFor(@Nullable ItemStack stack, @Nullable UUID playerId, @Nullable String group) {
        if (stack == null || stack.isEmpty() || playerId == null) {
            return false;
        }
        return keeps(stampOf(stack), playerId, group);
    }

    /** {@code stack} kept for {@code playerId} in {@code group}; the same stack when it already is. */
    @Nonnull
    public static ItemStack keep(@Nonnull ItemStack stack, @Nonnull UUID playerId, @Nullable String group) {
        if (stack.isEmpty()) {
            return stack;
        }
        Map<String, String[]> stamp = stampOf(stack);
        if (keeps(stamp, playerId, group)) {
            return stack;
        }
        return stack.withMetadata(METADATA_KEY, STAMP_CODEC, stamped(stamp, playerId, group));
    }

    /**
     * Does {@code stamp} keep a stack for {@code playerId} in {@code group}? Only that group's own entry counts,
     * matched without regard to case; a null stamp, player or entry keeps nothing. Pure.
     */
    public static boolean keeps(@Nullable Map<String, String[]> stamp, @Nullable UUID playerId,
            @Nullable String group) {
        if (stamp == null || playerId == null) {
            return false;
        }
        String[] keepers = stamp.get(UseGateAsset.groupKey(group));
        if (keepers == null) {
            return false;
        }
        String wanted = playerId.toString();
        for (String keeper : keepers) {
            if (wanted.equalsIgnoreCase(keeper)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code stamp} with {@code playerId} added to {@code group}'s keepers, every other entry kept; a new map, the
     * given one untouched. Pure.
     */
    @Nonnull
    public static Map<String, String[]> stamped(@Nullable Map<String, String[]> stamp, @Nonnull UUID playerId,
            @Nullable String group) {
        Map<String, String[]> next = new HashMap<>();
        if (stamp != null) {
            for (Map.Entry<String, String[]> entry : stamp.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    next.put(entry.getKey(), entry.getValue().clone());
                }
            }
        }
        if (keeps(next, playerId, group)) {
            return next;
        }
        String key = UseGateAsset.groupKey(group);
        String[] keepers = next.get(key);
        String[] grown = keepers == null ? new String[1] : Arrays.copyOf(keepers, keepers.length + 1);
        grown[grown.length - 1] = playerId.toString();
        next.put(key, grown);
        return next;
    }

    /**
     * Stamp, for this player, every stack they carry or wear for each group whose present item-axis gate refuses
     * them right now ({@code group} null for every group, else only that group), replacing each in its slot.
     * Answers how many stacks it stamped. World thread; a player with no inventory stamps nothing.
     */
    public static int keepCarried(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable String group) {
        int stamped = 0;
        try {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            UUID playerId = playerRef == null ? null : playerRef.getUuid();
            if (playerId == null || UseGates.nothingToAsk()) {
                return 0;
            }
            for (int section : SECTIONS) {
                ComponentType<EntityStore, ? extends InventoryComponent> type =
                        InventoryComponent.getComponentTypeById(section);
                InventoryComponent component = type == null ? null : store.getComponent(ref, type);
                ItemContainer container = component == null ? null : component.getInventory();
                if (container == null) {
                    continue;
                }
                for (short slot = 0; slot < container.getCapacity(); slot++) {
                    ItemStack stack = container.getItemStack(slot);
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    // A carried piece is kept for whichever item axis would refuse it: held for a tool or a
                    // weapon, worn for armor waiting in a bag; once per refusing group.
                    Set<String> groups = UseGates.refusingGroups(store, ref, stack, group);
                    if (groups.isEmpty()) {
                        continue;
                    }
                    ItemStack kept = stack;
                    for (String refusing : groups) {
                        kept = keep(kept, playerId, refusing);
                    }
                    // Unfiltered: an armor slot's own filter would refuse the very piece being kept.
                    container.setItemStackForSlot(slot, kept, false);
                    stamped++;
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[usegate] keeping a player's gear failed: " + t.getMessage());
        }
        return stamped;
    }

    @Nullable
    private static Map<String, String[]> stampOf(@Nonnull ItemStack stack) {
        try {
            return stack.getFromMetadataOrNull(METADATA_KEY, STAMP_CODEC);
        } catch (Throwable t) {
            return null;
        }
    }
}
