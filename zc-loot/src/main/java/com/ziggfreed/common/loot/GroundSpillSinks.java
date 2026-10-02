package com.ziggfreed.common.loot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.reward.NativeLootService;
import com.ziggfreed.common.inventory.InventoryGrant;
import com.ziggfreed.common.util.SafeLog;

import org.joml.Vector3d;

/**
 * THE way a loot pass hands items over in the world: the {@link LootEngine.ItemSink} and
 * {@link LootEngine.DropListSink} pair for a site whose loot lands in the player's bag when there is
 * room and on the ground when there is not, or on the ground outright.
 *
 * <p>Every stack a pass hands over goes the same way, whether the grant named it outright or a native
 * drop list rolled it: into the inventory first (when one is set), and whatever does not fit into ONE
 * ground pile per hand-over. A drop list is rolled through the engine's own roll
 * ({@link NativeLootService#rollNative}), and its overflow lands as one pile rather than one per
 * stack, so the pile a player walks up to matches what they were told they found.
 *
 * <p>Three orthogonal knobs, each independent of the others:
 * <ul>
 *   <li><b>Where the ground pile lands</b>: the {@link Ground} the preset is built {@link #at}. The
 *       position-and-facing form spawns through {@link NativeLootService#spawnInWorld}, the tick-safe
 *       pair form; a site with its own drop routine (a position it reads at drop time, a sink it
 *       shares with other paths) passes that routine as the {@code Ground}.</li>
 *   <li><b>Whether the inventory is tried first</b>: {@link Builder#inventoryFirst(Player)}
 *       (hotbar when the whole stack fits, then backpack storage, through
 *       {@link InventoryGrant}), or any {@link Inventory} step. Unset, everything spills on the
 *       ground.</li>
 *   <li><b>How a landed count is reported</b>: by default a stack counts as landed only when it
 *       reached the inventory or the ground drop answered that it landed, so a pile that went
 *       nowhere is never reported as found. {@link Builder#countFailedDrops(boolean)} counts every
 *       stack handed to the ground, whatever the drop answered.</li>
 * </ul>
 *
 * <p>World-thread only, like the inventory and the spawn it drives. A failure on one stack is
 * warned and costs that stack alone, never the rest of the hand-over.
 */
public final class GroundSpillSinks {

    /** Where a pile that did not go into the inventory lands. */
    @FunctionalInterface
    public interface Ground {
        /**
         * Drop {@code stacks} on the ground as one pile, answering whether it landed. Never called
         * with an empty list.
         */
        boolean drop(@Nonnull List<ItemStack> stacks);
    }

    /** The inventory step tried before the ground. */
    @FunctionalInterface
    public interface Inventory {
        /** Put {@code stack} in the inventory if it fits whole, answering whether it did. */
        boolean accept(@Nonnull ItemStack stack);
    }

    @Nonnull private final Ground ground;
    @Nullable private final Inventory inventory;
    private final boolean countFailedDrops;
    @Nonnull private final Consumer<String> warn;
    @Nonnull private final Function<String, List<ItemStack>> roller;

    private GroundSpillSinks(@Nonnull Builder b) {
        this.ground = b.ground;
        this.inventory = b.inventory;
        this.countFailedDrops = b.countFailedDrops;
        this.warn = b.warn;
        this.roller = b.roller;
    }

    /** A preset whose ground pile lands wherever {@code ground} drops it. */
    @Nonnull
    public static Builder at(@Nonnull Ground ground) {
        return new Builder(ground);
    }

    /**
     * A preset whose ground pile lands at {@code position}, facing {@code rotation}, through the
     * tick-safe {@link NativeLootService#spawnInWorld(Store, CommandBuffer, Vector3d, Rotation3f, List)}:
     * the form for a site running inside a system tick, which holds the tick's buffer.
     */
    @Nonnull
    public static Builder at(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Vector3d position, @Nonnull Rotation3f rotation) {
        return new Builder(stacks -> NativeLootService.spawnInWorld(store, commandBuffer, position, rotation,
                new ArrayList<>(stacks)));
    }

    /** The item sink: one {@code count}-sized stack of {@code itemId}, answering how many landed. */
    @Nonnull
    public LootEngine.ItemSink items() {
        return (itemId, count) -> {
            int landed = 0;
            for (Integer quantity : spill(List.of(new ItemStack(itemId, count))).values()) {
                landed += quantity;
            }
            return landed;
        };
    }

    /** The drop-list sink: one native roll of the list, handed over as {@link #spill} does. */
    @Nonnull
    public LootEngine.DropListSink dropLists() {
        return dropListId -> {
            List<ItemStack> rolled = roller.apply(dropListId);
            if (rolled == null || rolled.isEmpty()) {
                return Map.of();
            }
            return spill(rolled);
        };
    }

    /** Sets {@code builder}'s items and drop-lists leaves to this preset, answering the builder. */
    @Nonnull
    public LootEngine.Sinks.Builder into(@Nonnull LootEngine.Sinks.Builder builder) {
        return builder.items(items()).dropLists(dropLists());
    }

    /**
     * Hand {@code stacks} over by this preset's rules, answering what LANDED ({@code itemId ->}
     * quantity, in hand-over order): each stack into the inventory when one is set and it fits, then
     * everything left over dropped on the ground in ONE call. A null stack, or one naming no item, is
     * skipped.
     */
    @Nonnull
    public Map<String, Integer> spill(@Nonnull List<ItemStack> stacks) {
        Map<String, Integer> landed = new LinkedHashMap<>();
        List<ItemStack> overflow = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getItemId() == null) {
                continue;
            }
            try {
                // The quantity is read BEFORE the inventory takes the stack, and counted only once it
                // is in: a stack that went nowhere does not exist, so it is never reported as found.
                int quantity = stack.getQuantity();
                if (inventory != null && inventory.accept(stack)) {
                    landed.merge(stack.getItemId(), quantity, Integer::sum);
                } else {
                    overflow.add(stack);
                }
            } catch (Throwable t) {
                report("could not hand over '" + stack.getItemId() + "': " + t);
            }
        }
        if (overflow.isEmpty()) {
            return landed;
        }
        if (dropOnGround(overflow) || countFailedDrops) {
            for (ItemStack dropped : overflow) {
                landed.merge(dropped.getItemId(), dropped.getQuantity(), Integer::sum);
            }
        }
        return landed;
    }

    private boolean dropOnGround(@Nonnull List<ItemStack> pile) {
        try {
            return ground.drop(pile);
        } catch (Throwable t) {
            report("could not drop " + pile.size() + " stack(s) on the ground: " + t);
            return false;
        }
    }

    private void report(@Nonnull String message) {
        try {
            warn.accept(message);
        } catch (Throwable ignored) {
            // A warn sink that throws costs its own line, never the hand-over.
        }
    }

    /** The preset's knobs; every one is optional except where the ground pile lands. */
    public static final class Builder {

        @Nonnull private final Ground ground;
        @Nullable private Inventory inventory;
        private boolean countFailedDrops;
        @Nonnull private Consumer<String> warn = SafeLog::warn;
        @Nonnull private Function<String, List<ItemStack>> roller = NativeLootService::rollNative;

        private Builder(@Nonnull Ground ground) {
            this.ground = ground;
        }

        /**
         * Try {@code player}'s inventory first: the hotbar when the whole stack fits there, then
         * backpack storage ({@link InventoryGrant#grant}). Null means ground only.
         */
        @Nonnull
        public Builder inventoryFirst(@Nullable Player player) {
            this.inventory = player == null ? null : stack -> {
                boolean[] overflowed = {false};
                InventoryGrant.grant(player, stack, rest -> overflowed[0] = true);
                return !overflowed[0];
            };
            return this;
        }

        /** Try {@code inventory} first, whatever it is. Null means ground only. */
        @Nonnull
        public Builder inventory(@Nullable Inventory inventory) {
            this.inventory = inventory;
            return this;
        }

        /**
         * True to count every stack handed to the ground as landed, whatever the drop answered.
         * Default false: a pile counts only when the drop answered that it landed.
         */
        @Nonnull
        public Builder countFailedDrops(boolean countFailedDrops) {
            this.countFailedDrops = countFailedDrops;
            return this;
        }

        /** Where a stack that could not be handed over is reported. Default {@code SafeLog.warn}. */
        @Nonnull
        public Builder warn(@Nullable Consumer<String> warn) {
            this.warn = warn == null ? SafeLog::warn : warn;
            return this;
        }

        /** Test seam: the drop-list roll, in place of the engine's native one. */
        @Nonnull
        Builder roller(@Nonnull Function<String, List<ItemStack>> roller) {
            this.roller = roller;
            return this;
        }

        @Nonnull
        public GroundSpillSinks build() {
            return new GroundSpillSinks(this);
        }
    }
}
