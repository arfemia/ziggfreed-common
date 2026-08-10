package com.ziggfreed.common.cast;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;

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

    /** How many recently-removed world names the double-fan-out guard remembers. */
    private static final int RECENTLY_REMOVED_MAX = 128;

    /** Insertion-ordered so the oldest name is the one evicted when the guard is full. */
    private static final Set<String> RECENTLY_REMOVED = new LinkedHashSet<>();

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
     * Fan out to every registered evictor for a removed world. Called from
     * {@code ZiggfreedCommonPlugin}'s own {@code RemoveWorldEvent} listener, and historically from
     * each consumer's listener too; each evictor is guarded so a crashed evictor can never leak an
     * unloaded world into the other partitions.
     *
     * <p><b>Idempotent per world, and that matters now.</b> Several installed mods each running
     * their own {@code RemoveWorldEvent} listener means this fans out once per listener, not once
     * per world. That is harmless for an evictor that is a {@code map::remove}, but it is
     * CORRUPTING for one that maintains a reference count, which the chunk-pin bookkeeping does.
     * So a world already evicted is skipped, and a re-added world clears its own mark through
     * {@link #onWorldAdded}.
     */
    public static void onWorldRemoved(@Nonnull World world) {
        if (!markRemoved(world)) {
            return;
        }
        for (Consumer<World> evictor : EVICTORS) {
            try {
                evictor.accept(world);
            } catch (Throwable t) {
                warn("WorldEvictors evictor failed for world "
                        + world.getName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Forget that a world of this name was removed, so a world added under a name that was used
     * before is evicted properly when IT is removed. Call from an {@code AddWorldEvent} listener.
     */
    public static void onWorldAdded(@Nonnull World world) {
        String name = nameOf(world);
        if (name != null) {
            synchronized (RECENTLY_REMOVED) {
                RECENTLY_REMOVED.remove(name);
            }
        }
    }

    /**
     * Record that {@code world} is being evicted; false when it already was. Keyed by world NAME
     * rather than by the object, because holding a removed world's object alive is exactly the
     * leak eviction exists to prevent. Bounded, so the guard cannot grow without limit on a server
     * that creates and destroys instance worlds all day.
     */
    private static boolean markRemoved(@Nonnull World world) {
        String name = nameOf(world);
        if (name == null) {
            return true; // Unreadable name: never suppress an eviction on a guess.
        }
        synchronized (RECENTLY_REMOVED) {
            if (!RECENTLY_REMOVED.add(name)) {
                return false;
            }
            while (RECENTLY_REMOVED.size() > RECENTLY_REMOVED_MAX) {
                java.util.Iterator<String> it = RECENTLY_REMOVED.iterator();
                it.next();
                it.remove();
            }
            return true;
        }
    }

    @Nullable
    private static String nameOf(@Nonnull World world) {
        try {
            return world.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void warn(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
