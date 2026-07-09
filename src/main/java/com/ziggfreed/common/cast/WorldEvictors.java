package com.ziggfreed.common.cast;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * The single seam that resolves "which world is this entity in" and fans out per-world
 * cleanup when a world unloads.
 *
 * <p>World identity is the engine-stable chain {@code Ref.getStore()} ->
 * {@code store.getExternalData()} ({@link EntityStore}) -> {@code getWorld()}; all are
 * {@code @Nonnull} field reads, so {@link #worldOf} is cheap on hot paths.
 *
 * <p>Eviction: a per-world queue partition (a DOT / heal / zone / beam / projectile queue,
 * each keyed by {@link World}) registers an evictor via {@link #registerEvictor}; when the
 * consumer's {@code RemoveWorldEvent} listener calls {@link #onWorldRemoved(World)} every
 * registered evictor gets the unloaded world in ONE place instead of many divergent cleanup
 * paths. A guard around each evictor call keeps one bad evictor from starving the rest.
 *
 * <p><b>JVM-global by design (static):</b> eviction fan-out must reach every consumer's
 * per-world queues in one server process, so the evictor list is a JVM-global static, not a
 * per-consumer instance. The registrant list only grows (evictors live for the process).
 */
public final class WorldEvictors {

    private static final CopyOnWriteArrayList<Consumer<World>> EVICTORS = new CopyOnWriteArrayList<>();

    private WorldEvictors() {
    }

    @Nonnull
    public static World worldOf(@Nonnull Store<EntityStore> store) {
        return store.getExternalData().getWorld();
    }

    @Nonnull
    public static World worldOf(@Nonnull Ref<EntityStore> ref) {
        return ref.getStore().getExternalData().getWorld();
    }

    /**
     * Register a per-world cleanup callback (typically a queue partition's
     * {@code map::remove}). Invoked for the removed world on {@link #onWorldRemoved}
     * so every registered partition drops the world's queue at once.
     */
    public static void registerEvictor(@Nonnull Consumer<World> evictor) {
        EVICTORS.add(evictor);
    }

    /**
     * Fan out to every registered evictor for a removed world. Call from the consumer's
     * {@code RemoveWorldEvent} listener; each evictor is guarded so a crashed evictor
     * can never leak an unloaded world into the other partitions.
     */
    public static void onWorldRemoved(@Nonnull World world) {
        for (Consumer<World> evictor : EVICTORS) {
            try {
                evictor.accept(world);
            } catch (Throwable t) {
                warn("WorldEvictors evictor failed for world "
                        + world.getName() + ": " + t.getMessage());
            }
        }
    }

    private static void warn(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
