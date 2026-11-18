package com.ziggfreed.common.proc;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The ProjectileMiss moment's producer: a projectile a player launched that lands without having struck a creature is
 * their ProjectileMiss, about that projectile's asset id, heard the moment it lands ({@link Landed}: the game's legacy
 * projectile stops flying at its impact, and a miss then sits where it landed for its asset's {@code DeadTimeMiss},
 * ten seconds unless written, before the game removes it), or, for one never seen landing, as it leaves the world
 * ({@link Left}). A projectile's hit on any creature, even one the hit could not hurt, marks it struck ({@link #struck},
 * heard by {@link ProcSystems.Taken} inside the impact, before the projectile stops flying), so it never misses. Each
 * projectile is answered once. A projectile unloaded with its chunk is no miss.
 *
 * <p>It reads the game's thrown and shot projectiles that carry no chains of their own (a {@code LaunchProjectile}
 * projectile: a thrown spear, a mob's shot); a projectile config whose {@code Interactions} hold a
 * {@code ProjectileMiss} chain runs that chain on a miss instead, so its author answers it there.
 *
 * <p><b>What it was thrown from, and whether the throw spent it.</b> The game's projectile names no item, so the item
 * is read where the throw began: each chain a player starts holding an item is noted ({@link #chainStarted}: the
 * chain's own held stack, its durability and metadata included, and the slot it sat in), and a projectile that player
 * launches next takes that note as what it flew from, the n-th projectile of a note being its n-th throw. A throw
 * spends its item a moment after the projectile leaves (the game's spear throw takes one from the stack in a fork that
 * runs on the next tick), so the spend is heard on its own ({@link Spent}: the engine's inventory change taking that
 * item from that slot within {@link #SPEND_WINDOW_NANOS} of the throw, never one within that time of the player asking
 * to drop from that slot, {@link Dropped}), and a throw that spent nothing (a talent's throw that keeps the spear in
 * hand) has nothing to give back. A miss hands the item on as the moment's {@code thrown}, and a proc's
 * {@code ReturnThrown} asks for it while the miss is answered ({@link #answering}), claiming it after its wait
 * ({@link Thrown#claim}: one of the item, once, only when its throw spent one), and takes the projectile still lying
 * where it landed out of the world ({@link Thrown#takeFromWorld}), so the item never stands there twice. Noted only
 * while a live proc asks for ProjectileMiss. World thread; the struck set and the flight notes hold projectiles weakly,
 * so one that left is never kept.
 */
final class ProjectileMisses {

    /**
     * How soon after a throw the item it spends must leave the slot it was thrown from to count as that throw's: the
     * game's spend lands a tick or two behind the launch, so a few ticks' margin, and no time for a hand to drop or
     * move the item in between.
     */
    static final long SPEND_WINDOW_NANOS = 200_000_000L;

    /** The shortest a {@code ReturnThrown} waits, so the throw's own spend, a tick behind the throw, is always in. */
    static final long MIN_RETURN_MS = 500L;

    /**
     * How long a running chain's note stands against a chain started on another slot (an ability pressed during a held
     * throw), so a throw keeps its own note, while that chain has launched nothing yet.
     */
    static final long KEEP_NOTE_NANOS = 3_000_000_000L;

    /**
     * A chain a player began holding an item: the chain, when it began, the stack as it began, where it sat (the
     * inventory section, its container and slot), how many projectiles the player launched under it ({@code launched},
     * the last at {@code launchedNanos}) and how many of the item those throws took from that slot ({@code spent}).
     * Touched by the player's own world thread alone.
     */
    private static final class Held {
        @Nonnull final InteractionChain chain;
        final long startedNanos;
        @Nonnull final ItemStack item;
        final int section;
        @Nullable final ItemContainer container;
        final int slot;
        int launched;
        long launchedNanos;
        int spent;

        Held(@Nonnull InteractionChain chain, long startedNanos, @Nonnull ItemStack item, int section,
                @Nullable ItemContainer container, int slot) {
            this.chain = chain;
            this.startedNanos = startedNanos;
            this.item = item;
            this.section = section;
            this.container = container;
            this.slot = slot;
        }

        /** Is it the same slot of the same inventory? */
        boolean sameSlot(@Nullable ItemContainer otherContainer, int otherSlot) {
            return container == otherContainer && slot == otherSlot;
        }
    }

    /** A player's latest request to drop an item: from which section and slot, and when. */
    private record DropAsk(int section, int slot, long nanos) {
    }

    /**
     * One projectile's note: the chain note it flew from, which of that chain's throws it was, and the projectile.
     * Claimed once, by the first {@code ReturnThrown} whose wait ends, and only while its throw spent the item.
     */
    static final class Thrown {
        @Nonnull private final Held held;
        private final int order;
        /** Held weakly: a flight note is this projectile's weak map's value, which must never keep its key alive. */
        @Nonnull private final WeakReference<Ref<EntityStore>> projectile;
        private boolean claimed;

        private Thrown(@Nonnull Held held, int order, @Nonnull Ref<EntityStore> projectile) {
            this.held = held;
            this.order = order;
            this.projectile = new WeakReference<>(projectile);
        }

        /** One of the item it flew from, as it left the hand: what the moment reports. */
        @Nonnull
        ItemStack item() {
            return held.item.withQuantity(1);
        }

        /**
         * One of the item it flew from, once, when its throw spent one (its order among the chain's throws is within
         * what they took from the slot); else null. The holder's world thread.
         */
        @Nullable
        ItemStack claim() {
            if (claimed || order > held.spent) {
                return null;
            }
            claimed = true;
            return item();
        }

        /**
         * Take the projectile, still lying where it landed, out of {@code store} (a world task, outside any tick):
         * the item it stood for is going back to its thrower. Nothing when it already left.
         */
        void takeFromWorld(@Nonnull Store<EntityStore> store) {
            Ref<EntityStore> ref = projectile.get();
            if (ref != null && ref.isValid() && ref.getStore() == store) {
                store.removeEntity(ref, RemoveReason.REMOVE);
            }
        }
    }

    /** A player's projectile in flight: what it flew from (null when no note was taken) and whether it is answered. */
    private static final class Flight {
        @Nullable final Thrown thrown;
        boolean answered;

        Flight(@Nullable Thrown thrown) {
            this.thrown = thrown;
        }
    }

    private static final Set<Ref<EntityStore>> STRUCK =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final Map<Ref<EntityStore>, Flight> FLIGHTS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Per player, the note of the latest chain they began holding an item. */
    private static final Map<Ref<EntityStore>, Held> LAST_HELD = new ConcurrentHashMap<>();

    /** Per player, the note their latest projectile flew from: the one a spend that follows it is credited to. */
    private static final Map<Ref<EntityStore>, Held> LAST_THROWN = new ConcurrentHashMap<>();

    /** Per player, their latest request to drop an item. */
    private static final Map<Ref<EntityStore>, DropAsk> LAST_DROP = new ConcurrentHashMap<>();

    /** The projectile whose miss this thread is answering, while its procs run ({@link #miss}). */
    private static final ThreadLocal<Thrown> ANSWERING = new ThreadLocal<>();

    private ProjectileMisses() {
    }

    /** {@code damage} landed on a creature: when a projectile dealt it, that projectile struck. */
    static void struck(@Nonnull Damage damage) {
        if (damage.getSource() instanceof Damage.ProjectileSource source && source.getProjectile() != null) {
            STRUCK.add(source.getProjectile());
        }
    }

    /** Did the projectile {@code ref} strike a creature? */
    static boolean hasStruck(@Nonnull Ref<EntityStore> ref) {
        return STRUCK.contains(ref);
    }

    /**
     * The player {@code holder} started {@code chain} ({@code context}): note the stack they held as it began and the
     * slot it sat in, what a projectile they launch next flew from. A chain started empty-handed leaves the last note
     * alone, and so does one holding another slot's item while the noted chain still runs, has launched nothing and is
     * younger than {@link #KEEP_NOTE_NANOS} (an ability pressed during a held throw), so the throw keeps its own note.
     */
    static void chainStarted(@Nonnull Ref<EntityStore> holder, @Nonnull InteractionChain chain,
            @Nullable InteractionContext context, long nanos) {
        ItemStack held = context == null ? null : context.getHeldItem();
        if (held == null || held.isEmpty()) {
            return;
        }
        ItemContainer container = context.getHeldItemContainer();
        int slot = context.getHeldItemSlot();
        Held running = LAST_HELD.get(holder);
        if (running != null && running.chain != chain && !running.sameSlot(container, slot)
                && running.launched == 0 && nanos - running.startedNanos <= KEEP_NOTE_NANOS
                && running.chain.getServerState() == InteractionState.NotFinished) {
            return;
        }
        LAST_HELD.put(holder, new Held(chain, nanos, held, context.getHeldItemSectionId(), container, slot));
    }

    /** Forget a player who left their store. */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        LAST_HELD.remove(ref);
        LAST_THROWN.remove(ref);
        LAST_DROP.remove(ref);
    }

    /** The projectile whose miss this thread is answering right now, or null outside a ProjectileMiss's procs. */
    @Nullable
    static Thrown answering() {
        return ANSWERING.get();
    }

    /**
     * A projectile entering its store: when a player launched it while a proc asks, note its flight and the item it
     * flew from (their latest chain note).
     */
    static void spawned(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            if (reason != AddReason.SPAWN || !Procs.listens(ProcTrigger.PROJECTILE_MISS)) {
                return;
            }
            Ref<EntityStore> shooter = shooterOf(ref, store, commandBuffer);
            if (shooter == null) {
                return;
            }
            Held held = LAST_HELD.get(shooter);
            Thrown thrown = null;
            if (held != null) {
                held.launched++;
                held.launchedNanos = System.nanoTime();
                LAST_THROWN.put(shooter, held);
                thrown = new Thrown(held, held.launched, ref);
            }
            FLIGHTS.put(ref, new Flight(thrown));
        } catch (Throwable t) {
            Procs.failed("a projectile's launch", t);
        }
    }

    /**
     * The player {@code holder}'s inventory changed ({@code event}): the item their latest throw flew from leaving the
     * slot it was thrown from, soon after that throw, is the throw spending it. Only a slot's own count going down
     * counts (a swap for another item, a move, a durability change take nothing), never one soon after the player
     * asked to drop from that slot (a drop is no throw, even one asked in the throw's own tick), and never more than
     * the throws made.
     */
    static void inventoryChanged(@Nonnull Ref<EntityStore> holder, @Nonnull InventoryChangeEvent event) {
        Held held = LAST_THROWN.get(holder);
        long now = System.nanoTime();
        if (held == null || held.spent >= held.launched || held.container == null
                || event.getItemContainer() != held.container
                || now - held.launchedNanos > SPEND_WINDOW_NANOS
                || !(event.getTransaction() instanceof SlotTransaction slot) || !slot.succeeded()
                || slot.getSlot() != held.slot) {
            return;
        }
        DropAsk drop = LAST_DROP.get(holder);
        if (drop != null && drop.section() == held.section && drop.slot() == held.slot
                && now - drop.nanos() <= SPEND_WINDOW_NANOS) {
            return;
        }
        ItemStack before = slot.getSlotBefore();
        if (before == null || before.isEmpty() || !before.getItemId().equals(held.item.getItemId())) {
            return;
        }
        ItemStack after = slot.getSlotAfter();
        int left;
        if (after == null || after.isEmpty()) {
            left = 0;
        } else if (after.getItemId().equals(before.getItemId())) {
            left = after.getQuantity();
        } else {
            return;
        }
        int took = before.getQuantity() - left;
        if (took > 0) {
            held.spent = Math.min(held.launched, held.spent + took);
        }
    }

    /**
     * The player {@code holder} asked to drop from {@code section}'s {@code slot}: nothing taken from that slot within
     * {@link #SPEND_WINDOW_NANOS} of it counts as a throw's spend. The game asks before it takes the item.
     */
    static void dropRequested(@Nonnull Ref<EntityStore> holder, int section, int slot) {
        LAST_DROP.put(holder, new DropAsk(section, slot, System.nanoTime()));
    }

    /**
     * A noted projectile seen in flight ({@code states}): the moment it stops flying it has landed, and, having struck
     * no creature, missed. Answered once.
     */
    static void watched(@Nonnull Ref<EntityStore> ref, @Nullable MovementStates states,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Flight flight = FLIGHTS.get(ref);
        if (flight == null || flight.answered || states == null || states.flying) {
            return;
        }
        flight.answered = true;
        if (STRUCK.contains(ref)) {
            return;
        }
        miss(ref, flight.thrown, store, commandBuffer);
    }

    /**
     * A projectile leaving its store: one never seen landing (removed in the tick it landed, or never noted) is its
     * shooter's ProjectileMiss now, when it was removed (not unloaded), struck no creature, and a player launched it.
     * Never throws.
     */
    static void left(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            boolean struck = STRUCK.remove(ref);
            Flight flight = FLIGHTS.remove(ref);
            if ((flight != null && flight.answered) || reason != RemoveReason.REMOVE || struck
                    || !Procs.listens(ProcTrigger.PROJECTILE_MISS)) {
                return;
            }
            miss(ref, flight == null ? null : flight.thrown, store, commandBuffer);
        } catch (Throwable t) {
            Procs.failed("a projectile's miss", t);
        }
    }

    /** Fire {@code ref}'s ProjectileMiss for its live player shooter, {@code thrown} answerable while its procs run. */
    private static void miss(@Nonnull Ref<EntityStore> ref, @Nullable Thrown thrown, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ProjectileComponent projectile = commandBuffer.getComponent(ref, ProjectileComponent.getComponentType());
        Ref<EntityStore> shooter = shooterOf(ref, store, commandBuffer);
        if (projectile == null || shooter == null) {
            return;
        }
        ANSWERING.set(thrown);
        try {
            Procs.fire(new ProcMoment(ProcTrigger.PROJECTILE_MISS, store, commandBuffer, shooter, null, null, 1.0,
                    null, null, null, null, 0.0, idOf(projectile), null, thrown == null ? null : thrown.item()));
        } finally {
            ANSWERING.remove();
        }
    }

    /** The live player who launched the projectile {@code ref}, or null. */
    @Nullable
    private static Ref<EntityStore> shooterOf(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ProjectileComponent projectile = commandBuffer.getComponent(ref, ProjectileComponent.getComponentType());
        UUID creator = projectile == null ? null : projectile.getCreatorUuid();
        Ref<EntityStore> shooter = creator == null ? null : store.getExternalData().getRefFromUUID(creator);
        if (shooter == null || !shooter.isValid()
                || commandBuffer.getComponent(shooter, PlayerRef.getComponentType()) == null) {
            return null;
        }
        return shooter;
    }

    @Nullable
    private static String idOf(@Nonnull ProjectileComponent projectile) {
        String id = projectile.getProjectileAssetName();
        return id == null || id.isBlank() ? null : id.trim();
    }

    /** Hears every projectile of the game's own kind entering ({@link #spawned}) and leaving ({@link #left}). */
    public static final class Left extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return ProjectileComponent.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            spawned(ref, reason, store, commandBuffer);
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            left(ref, reason, store, commandBuffer);
        }
    }

    /** Watches each noted projectile in flight for the moment it lands ({@link #watched}). */
    public static final class Landed extends EntityTickingSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = Query.and(ProjectileComponent.getComponentType(),
                MovementStatesComponent.getComponentType());

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
            if (FLIGHTS.isEmpty()) {
                return;
            }
            super.tick(dt, systemIndex, store);
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                MovementStatesComponent states = chunk.getComponent(index, MovementStatesComponent.getComponentType());
                watched(chunk.getReferenceTo(index), states == null ? null : states.getMovementStates(), store,
                        commandBuffer);
            } catch (Throwable t) {
                Procs.failed("a projectile's landing", t);
            }
        }
    }

    /** Hears a player's inventory changes while a proc asks for ProjectileMiss: a throw spending its item. */
    public static final class Spent extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

        public Spent() {
            super(InventoryChangeEvent.class);
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InventoryChangeEvent event) {
            try {
                if (LAST_THROWN.isEmpty() || !Procs.listens(ProcTrigger.PROJECTILE_MISS)) {
                    return;
                }
                inventoryChanged(chunk.getReferenceTo(index), event);
            } catch (Throwable t) {
                Procs.failed("a throw's spend", t);
            }
        }
    }

    /** Hears a player asking to drop an item while a proc asks for ProjectileMiss: no throw's spend beside it. */
    public static final class Dropped extends EntityEventSystem<EntityStore, DropItemEvent.PlayerRequest> {

        public Dropped() {
            super(DropItemEvent.PlayerRequest.class);
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull DropItemEvent.PlayerRequest event) {
            try {
                if (!Procs.listens(ProcTrigger.PROJECTILE_MISS)) {
                    return;
                }
                dropRequested(chunk.getReferenceTo(index), event.getInventorySectionId(), event.getSlotId());
            } catch (Throwable t) {
                Procs.failed("a drop beside a throw", t);
            }
        }
    }
}
