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
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
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
 *       {@link InventoryGrant}), {@link Builder#pickupRoutedFirst(Player)} (wherever the player's own
 *       pickup settings route the item, partial stacks filled, through the engine's own give), or any
 *       {@link Inventory} step. Unset, everything spills on the ground.</li>
 *   <li><b>How a landed count is reported</b>: by default a stack counts as landed only when it
 *       reached the inventory or the ground drop answered that it landed, so a pile that went
 *       nowhere is never reported as found. {@link Builder#countFailedDrops(boolean)} counts every
 *       stack handed to the ground, whatever the drop answered.</li>
 * </ul>
 *
 * <p>A step that can take PART of a stack ({@link Inventory#offer}) keeps what fit and sends only the
 * rest to the pile, and the landed count reports exactly the part that went in. The preset never
 * copies or rebuilds a stack it is handed through {@link #spill}: what lands is that stack (or the
 * part the inventory did not take), metadata and all. Only the item sink builds a fresh stack, from
 * an id and a count, so a caller holding real stacks hands them over through {@link #spill}.
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

        /**
         * Offer {@code stack} to the inventory, answering the part that did NOT go in: null (or an
         * empty stack) when all of it did, {@code stack} itself when none of it did, or a smaller
         * stack of the same item when part of it did. {@link #spill} asks this.
         *
         * <p>This default is whole or nothing through {@link #accept}. A step that can fill part of a
         * stack overrides it; its {@code accept} may then have put part away while answering false,
         * so such a step is offered, never asked to accept.
         */
        @Nullable
        default ItemStack offer(@Nonnull ItemStack stack) {
            return accept(stack) ? null : stack;
        }
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

    /**
     * A preset whose ground pile lands where {@code entity} stands at drop time, lifted and facing as
     * the engine drops a dying entity's items ({@link NativeLootService#spawnAtEntity}): the corpse
     * ground, for a site running inside a system tick that holds the tick's buffer. The position is
     * read when the pile drops, so a site may build this before the entity's last move.
     */
    @Nonnull
    public static Builder atEntity(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> entity) {
        return new Builder(stacks -> NativeLootService.spawnAtEntity(store, commandBuffer, entity,
                new ArrayList<>(stacks)));
    }

    /**
     * The inventory step that gives a stack the way the engine gives a picked-up item
     * ({@code Player.giveItem}): into the container the player's own pickup settings choose for that
     * item (the hotbar or the backpack, by item type), topping up partial stacks of the same item
     * before taking an empty slot, and answering the part that did not fit. It fires no pickup
     * notice; the site says what landed itself.
     *
     * <p>{@code accessor} reads the player's settings and inventory: the entity's store outside a
     * tick, or the tick's buffer inside one.
     */
    @Nonnull
    public static Inventory pickupRouted(@Nonnull Ref<EntityStore> player, @Nonnull ComponentAccessor<EntityStore> accessor) {
        return new Inventory() {
            @Override
            public boolean accept(@Nonnull ItemStack stack) {
                ItemStack rest = offer(stack);
                return isNothing(rest);
            }

            @Override
            @Nullable
            public ItemStack offer(@Nonnull ItemStack stack) {
                if (!player.isValid()) {
                    return stack;
                }
                return remainderOf(Player.giveItem(stack, player, accessor), stack);
            }
        };
    }

    /**
     * What a give left over: the transaction's remainder, or the whole stack when the give failed
     * without naming one (the engine's shared failed-add answer names none).
     */
    @Nullable
    static ItemStack remainderOf(@Nullable ItemStackTransaction transaction, @Nonnull ItemStack offered) {
        if (transaction == null) {
            return offered;
        }
        ItemStack rest = transaction.getRemainder();
        if (rest == null && !transaction.succeeded()) {
            return offered;
        }
        return rest;
    }

    /** True when an inventory's answer leaves nothing over. */
    private static boolean isNothing(@Nullable ItemStack rest) {
        return rest == null || rest.getItemId() == null || rest.getQuantity() <= 0;
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
     * quantity, in hand-over order): each stack offered to the inventory when one is set
     * ({@link Inventory#offer}), the part that went in counted, then every part left over dropped on
     * the ground in ONE call. A null stack, or one naming no item, is skipped.
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
                // The quantity is read BEFORE the inventory takes the stack, and only the part that
                // went in is counted: a part that went nowhere does not exist, so it is never found.
                int quantity = stack.getQuantity();
                ItemStack rest = inventory == null ? stack : inventory.offer(stack);
                if (rest == stack) {
                    overflow.add(stack);
                } else if (isNothing(rest)) {
                    landed.merge(stack.getItemId(), quantity, Integer::sum);
                } else {
                    int took = quantity - Math.min(quantity, rest.getQuantity());
                    if (took > 0) {
                        landed.merge(stack.getItemId(), took, Integer::sum);
                    }
                    overflow.add(rest);
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

        /**
         * Try {@code player}'s inventory first the way the engine gives a picked-up item
         * ({@link GroundSpillSinks#pickupRouted}): the container the player's pickup settings choose,
         * partial stacks filled, only the rest to the ground, read through the player's own store (a
         * site inside a system tick builds the step with its buffer instead). Null, or a player with
         * no live reference, means ground only.
         */
        @Nonnull
        public Builder pickupRoutedFirst(@Nullable Player player) {
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            this.inventory = ref == null ? null : pickupRouted(ref, ref.getStore());
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
