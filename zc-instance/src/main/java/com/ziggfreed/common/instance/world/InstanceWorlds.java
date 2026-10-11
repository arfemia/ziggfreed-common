package com.ziggfreed.common.instance.world;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.instances.removal.IdleTimeoutCondition;
import com.hypixel.hytale.builtin.instances.removal.WorldEmptyCondition;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one launcher of engine instance copies in the library: spawn a copy of a pack's
 * {@code Server/Instances/<template>/} under a name zc chooses, send a player in and out, and close it.
 * The engine runs the world, its return points and its death rules; this class only sets what every zc
 * copy must carry and keeps the threading rules in one place.
 *
 * <p><b>What every copy carries.</b> {@link #spawn} hands the engine a config step, run after the
 * template's config loads and before the world starts, that sets:
 * <ul>
 *   <li>its removal conditions to native {@code WorldEmpty} AND {@code IdleTimeout} (the engine ANDs a
 *       world's conditions): {@code WorldEmpty} holds from the moment a copy that had a player is empty,
 *       {@code IdleTimeout} is the grace after that, so a copy closes once it has stood empty for the
 *       caller's {@code emptyCloseSeconds}. A copy no one reaches closes after the longer of WorldEmpty's
 *       own wait for a first arrival and that grace;</li>
 *   <li>{@code DeleteOnUniverseStart} and {@code DeleteOnRemove}, so a copy left open by a hard stop is
 *       deleted at the next start and a closed one leaves no folder;</li>
 *   <li>then the caller's own {@code extraConfig} (a spawn provider, say).</li>
 * </ul>
 *
 * <p><b>Names.</b> A copy is always spawned under zc's own world name, never keyed by an
 * {@code InstanceKey}: the engine hands back a live world of the requested name as it is and drops the
 * config step, so a spawn under a name that is live, or already being spawned here, fails its future
 * instead of returning a world without zc's settings.
 *
 * <p><b>Threading.</b> {@code spawnInstance} runs off-thread: consume its future with
 * {@code whenComplete}, never {@code .join()} it on a world thread. {@link #teleportIn} and {@link #exit}
 * run on the player's current world thread. {@code Universe.removeWorld} stops the world it removes and
 * waits for it, so it never runs on that world's own thread: {@link #removeOffThread} queues it on one
 * daemon thread, {@code zc-instance-remover}.
 */
public final class InstanceWorlds {

    private static final String LOG = "[instance] ";

    /** The removal condition's own key, decoded through its codec: it has no setter or sized constructor. */
    private static final String TIMEOUT_SECONDS_KEY = "TimeoutSeconds";

    /** The one thread {@code Universe.removeWorld} runs on; it starts on the first removal. */
    private static final ExecutorService REMOVER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "zc-instance-remover");
        thread.setDaemon(true);
        return thread;
    });

    /** Lower-cased names of the copies being spawned here now, so a second spawn of one name fails. */
    private static final Set<String> SPAWNING = ConcurrentHashMap.newKeySet();

    private InstanceWorlds() {
    }

    /**
     * Spawn a copy of {@code template} named {@code worldName}. Callable from any thread; the future
     * completes once the world is loaded, and fails when the name is taken, the template cannot be read
     * or {@code extraConfig} throws.
     *
     * @param template          the instance asset under {@code Server/Instances/}
     * @param worldName         zc's own name for the copy, live nowhere yet
     * @param forWorld          the world a player returns to on exit, unless they carry their own return
     * @param returnPoint       where in {@code forWorld} they return to
     * @param emptyCloseSeconds how long the copy stands empty before it closes, zero or more
     * @param extraConfig       a further step on the copy's config, run after zc's own; null for none
     */
    @Nonnull
    public static CompletableFuture<World> spawn(@Nonnull String template, @Nonnull String worldName,
            @Nonnull World forWorld, @Nonnull Transform returnPoint, double emptyCloseSeconds,
            @Nullable Consumer<WorldConfig> extraConfig) {
        if (template.isBlank() || worldName.isBlank()) {
            return refuse(template, worldName, "a copy needs a template and a world name");
        }
        if (!Double.isFinite(emptyCloseSeconds) || emptyCloseSeconds < 0) {
            return refuse(template, worldName, "its empty-close time is not zero or more seconds ("
                    + emptyCloseSeconds + ")");
        }
        InstancesPlugin plugin = InstancesPlugin.get();
        if (plugin == null) {
            return refuse(template, worldName, "the engine's Instances plugin is not loaded");
        }
        String key = worldName.toLowerCase(Locale.ROOT);
        if (!SPAWNING.add(key)) {
            return refuse(template, worldName, "a copy of that name is already being spawned");
        }
        try {
            if (Universe.get().getWorld(worldName) != null) {
                SPAWNING.remove(key);
                return refuse(template, worldName, "a world of that name is already live, and the engine"
                        + " would hand it back without zc's settings");
            }
            IdleTimeoutCondition idle = idleTimeout(emptyCloseSeconds);
            CompletableFuture<World> future = plugin.spawnInstance(template, worldName, forWorld, returnPoint,
                    config -> configure(config, idle, extraConfig));
            future.whenComplete((world, error) -> SPAWNING.remove(key));
            return future;
        } catch (Throwable t) {
            SPAWNING.remove(key);
            SafeLog.warn(LOG + "the copy '" + worldName + "' of '" + template + "' could not be spawned", t);
            return CompletableFuture.failedFuture(t);
        }
    }

    /**
     * Send a player into a copy that may still be loading. Run on the player's CURRENT world thread.
     *
     * @param overrideReturn the player's own return point (each member their own captured transform, so
     *                       each returns where they stood); null for the copy's return point
     * @param arrival        where in the copy the player lands; null for the copy's spawn provider
     */
    public static void teleportIn(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull CompletableFuture<World> worldFuture, @Nullable Transform overrideReturn,
            @Nullable Transform arrival) {
        InstancesPlugin.teleportPlayerToLoadingInstance(ref, accessor, worldFuture, overrideReturn, arrival);
    }

    /**
     * Send a player out of the copy they are in, to their own return point or the copy's. Run on the copy's
     * world thread. The future completes once the cross-world teleport lands. The engine throws an
     * {@code IllegalArgumentException} when the player is in no instance.
     */
    @Nonnull
    public static CompletableFuture<Void> exit(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> accessor) {
        return InstancesPlugin.exitInstance(ref, accessor);
    }

    /**
     * Close a copy as soon as it is empty, through the engine's own removal: it stops waiting for a first
     * arrival and drops the empty grace. It writes the copy's removal state, which the engine reads on the
     * copy's thread, so call it there when you can. A null world is ignored, and an engine failure is
     * logged, never thrown.
     */
    public static void safeRemove(@Nullable World world) {
        if (world == null) {
            return;
        }
        try {
            InstancesPlugin.safeRemoveInstance(world);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the copy '" + world.getName() + "' could not be flagged to close", t);
        }
    }

    /**
     * Remove a copy now, on the remover thread, never on the copy's own thread. A no-op when the copy is
     * already gone, or when its name now belongs to a newer world (copy names repeat).
     */
    public static void removeOffThread(@Nullable World world) {
        if (world == null) {
            return;
        }
        String name = world.getName();
        try {
            REMOVER.execute(() -> removeNow(world, name));
        } catch (RejectedExecutionException e) {
            SafeLog.fine(LOG + "the copy '" + name + "' was not removed: the library is shutting down");
        }
    }

    /** Stop the remover thread. Called once from the library's shutdown. */
    public static void shutdown() {
        REMOVER.shutdownNow();
    }

    private static void removeNow(@Nonnull World world, @Nonnull String name) {
        try {
            Universe universe = Universe.get();
            if (universe == null || universe.getWorld(name) != world) {
                return;
            }
            universe.removeWorld(name);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the copy '" + name + "' could not be removed", t);
        }
    }

    /** zc's settings on a copy's config, then the caller's. Runs before the world starts. */
    private static void configure(@Nonnull WorldConfig config, @Nonnull IdleTimeoutCondition idle,
            @Nullable Consumer<WorldConfig> extraConfig) {
        InstanceWorldConfig.ensureAndGet(config).setRemovalConditions(WorldEmptyCondition.INSTANCE, idle);
        config.setDeleteOnUniverseStart(true);
        config.setDeleteOnRemove(true);
        if (extraConfig != null) {
            extraConfig.accept(config);
        }
    }

    /**
     * An {@code IdleTimeout} of {@code seconds}, decoded through the condition's own codec, which is the
     * only way to size it: it has a no-argument constructor and no setter.
     */
    @Nonnull
    private static IdleTimeoutCondition idleTimeout(double seconds) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty(TIMEOUT_SECONDS_KEY, seconds);
        IdleTimeoutCondition condition =
                IdleTimeoutCondition.CODEC.decodeJson(RawJsonReader.fromJsonString(json.toString()), new ExtraInfo());
        if (condition == null) {
            throw new IOException("the idle timeout of " + seconds + " seconds decoded to nothing");
        }
        return condition;
    }

    @Nonnull
    private static CompletableFuture<World> refuse(@Nonnull String template, @Nonnull String worldName,
            @Nonnull String reason) {
        String message = "the copy '" + worldName + "' of '" + template + "' was not spawned: " + reason;
        SafeLog.warn(LOG + message);
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }
}
