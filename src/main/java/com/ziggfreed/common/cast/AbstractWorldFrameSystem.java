package com.ziggfreed.common.cast;

import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Abstract base for a per-world frame drain that runs once per world per frame. Hangs off
 * the player query so it ticks as long as at least one player is online in the world; the
 * query is only an "at least one player online" gate (it ignores the ref/index).
 *
 * <p>The gate: this system fires once per online player per frame. An internal per-world
 * {@link WorldFrameGate} (one gate per {@link World}, computeIfAbsent) lets only the FIRST
 * call per world per frame through via its millisecond CAS, so {@link #drainFrame} runs
 * exactly once per world per frame. The stamp is per-world, so two worlds ticking in the
 * same millisecond never steal each other's frame. The per-world gate map self-registers an
 * evictor with {@link WorldEvictors} in the constructor, so an unloaded world's gate is swept.
 *
 * <p>A concrete subclass implements {@link #drainFrame(World, Store, CommandBuffer)} and drains
 * its own per-world queues there (the exact body a monolithic tick would have after resolving
 * the world and winning the gate).
 *
 * <p><b>This class must NEVER be registered by ziggfreed-common.</b> Hytale's ECS system
 * registry is class-keyed (a second {@code registerSystem} with the same Class collides), so
 * each consumer registers its OWN concrete subclass with the server. Common ships the base only.
 */
public abstract class AbstractWorldFrameSystem extends EntityTickingSystem<EntityStore> {

    private final ConcurrentHashMap<World, WorldFrameGate> gates = new ConcurrentHashMap<>();

    protected AbstractWorldFrameSystem() {
        WorldEvictors.registerEvictor(gates::remove);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public final void tick(float dt, int index,
                           @Nonnull ArchetypeChunk<EntityStore> chunk,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        World world = WorldEvictors.worldOf(store);
        WorldFrameGate gate = gates.computeIfAbsent(world, k -> new WorldFrameGate());
        if (!gate.beginFrame()) {
            return;
        }
        drainFrame(world, store, commandBuffer);
    }

    /**
     * Drain this world's per-world queues once for this frame. Called exactly once per world
     * per frame after the frame gate is won, with the resolved {@link World}, the tick
     * {@link Store}, and the frame {@link CommandBuffer} (queue damage / component writes
     * through the command buffer, never the live store mid-tick).
     */
    protected abstract void drainFrame(@Nonnull World world,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull CommandBuffer<EntityStore> commandBuffer);
}
