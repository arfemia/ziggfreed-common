package com.ziggfreed.common.cast;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Abstract base for a per-world frame drain that runs once per world per frame. Extends the
 * engine's own query-less {@link TickingSystem} directly (not {@code EntityTickingSystem}):
 * {@code Store.tickInternal} calls {@link #tick(float, int, Store)} exactly once per
 * registered system per {@link Store} per server tick, with no per-entity fan-out at all -
 * confirmed against both the official shared source and the installed server's decompile
 * (vanilla's {@code TimeSystem}/{@code WorldTimeSystems.Ticking} use the identical base for
 * the same reason). Since a {@link Store}&lt;{@link EntityStore}&gt; is one world's store, this
 * genuinely IS "once per world per frame" with no dedup gate needed - the pre-1.6.0
 * {@code WorldFrameGate} millisecond CAS this class used to carry was a workaround for riding
 * a per-player {@code PlayerRef} query ({@code EntityTickingSystem} fires once per matching
 * entity); it is gone, and so is the once-per-online-player semantics that gate was covering
 * for. A world with zero players ticks its (empty) drains too now, same as any other loaded
 * world - cheap, since every drain here early-returns on an empty queue.
 *
 * <p>A concrete subclass implements {@link #drainFrame(World, Store, CommandBuffer)} and drains
 * its own per-world queues there. The {@link CommandBuffer} is obtained via
 * {@link Store#forEachChunk(java.util.function.BiConsumer)} (the only public route to one from a
 * query-less system - {@code Store.takeCommandBuffer()} is package-private): that call takes
 * ONE {@code CommandBuffer} for the whole invocation and threads the same instance to every
 * archetype-chunk callback, so capturing it on the first callback and skipping the rest yields
 * exactly one {@code drainFrame} call sharing that single buffer, still flushed by
 * {@code forEachChunk}'s own trailing {@code commandBuffer.consume()}. A store with zero
 * archetype chunks (no entities of any kind) skips the callback entirely that tick; in
 * practice a loaded world's store is never chunk-less, and even if it were there would be
 * nothing queued to drain.
 *
 * <p><b>This class must NEVER be registered by ziggfreed-common.</b> Hytale's ECS system
 * registry is class-keyed (a second {@code registerSystem} with the same Class collides), so
 * each consumer registers its OWN concrete subclass with the server. Common ships the base only.
 */
public abstract class AbstractWorldFrameSystem extends TickingSystem<EntityStore> {

    @Override
    public final void tick(final float dt, final int systemIndex, @Nonnull final Store<EntityStore> store) {
        final World world = WorldEvictors.worldOf(store);
        final boolean[] fired = {false};
        store.forEachChunk((chunk, commandBuffer) -> {
            if (fired[0]) {
                return;
            }
            fired[0] = true;
            drainFrame(world, store, commandBuffer);
        });
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
