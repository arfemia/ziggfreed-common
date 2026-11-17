package com.ziggfreed.common.instance.world;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.builtin.instances.config.InstanceEntityConfig;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.instances.config.WorldReturnPoint;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * A player's way between the overworld and the game's shared instances (the Forgotten Temple's copy, the temple hub's),
 * on the engine's own instance travel ({@code InstancesPlugin}), for a way the engine's {@code TeleportInstance} and
 * {@code ExitInstance} interactions cannot serve: a placed character's press-F, a conversation's answer, or a block
 * that must keep the return chain (the temple hub's pads, through {@code TravelDestinations} and {@code ZigOpen}).
 *
 * <p><b>Return points.</b> The engine keeps each player's way back as a chain: their current return point, the world
 * they go back to on leaving the instance they are in, and beneath it the return points of the instances they came
 * through. The two moves keep that chain the way a player would expect:
 * <ul>
 *   <li>{@link #toInstance} goes back where the player stood when their chain already passes through a copy of that
 *       instance (the hub's door back to the temple puts them beside the doorkeeper: in the newer copy holding its key
 *       when theirs emptied and closed meanwhile, or in one the engine respawns there when none does), and their own
 *       way out of it is restored, so the temple's exit still leads to the gateway they came in by. Otherwise they go to the copy's spawn, the way a portal with
 *       {@code PersonalReturnPoint} sends them, and where they stood is their way back.</li>
 *   <li>{@link #toOverworld} leaves every instance at once, for the first world in the chain that is no instance (where
 *       they stood when they first left it), else the default world's spawn.</li>
 * </ul>
 *
 * <p>Both run on the player's world thread, outside any system: a press-F arrives mid-system, so a caller there
 * queues them on a world task first ({@code TravelDestinations}).
 */
public final class InstanceTravel {

    private static final String LOG = "[instance] ";

    /** How a move went. */
    public enum Outcome {
        /** The player is on their way. */
        SENT,
        /** The player already stands where the move leads. */
        ALREADY_THERE,
        /** The move leads to an instance no loaded pack ships. */
        NO_SUCH_INSTANCE,
        /** The player is dead, loading in or already being moved. */
        BUSY,
        /** The engine refused; the line is logged. */
        FAILED
    }

    private InstanceTravel() {
    }

    /**
     * Send the player to the shared copy of the instance {@code template}: back where they stood in it when their
     * return chain passes through it, else to its spawn, keeping where they stand now as their way back.
     *
     * @param template the instance asset under {@code Server/Instances/}
     * @param key      the world key its one copy lives under, or null for the key its template pins (a template
     *                 with none spawns a copy per visit)
     */
    @Nonnull
    public static Outcome toInstance(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull String template, @Nullable String key) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || busy(ref, store, player)) {
            return Outcome.BUSY;
        }
        World world = store.getExternalData().getWorld();
        if (isCopyOf(world, template, key)) {
            return Outcome.ALREADY_THERE;
        }
        if (!templateExists(template)) {
            return Outcome.NO_SUCH_INSTANCE;
        }
        try {
            InstanceEntityConfig chain = store.getComponent(ref, InstanceEntityConfig.getComponentType());
            WorldReturnPoint back = chain == null ? null : chain.getReturnPoint();
            if (back != null && leadsInto(back, template, key)) {
                World there = copyHolding(back);
                if (there != null) {
                    // Their own way out of it, under the point that leads into it: theirs again once they are there,
                    // whether the copy they left still stands or a newer one holds its key (the engine lands them on
                    // the recorded spot in it). With no copy live, the engine respawns one at the key and reads the
                    // chain for its way out itself, so nothing is popped here.
                    WorldReturnPoint beyond = chain.popReturnPoint();
                    if (beyond != null && !beyond.getWorld().equals(back.getWorld())
                            && !beyond.getWorld().equals(there.getWorldConfig().getUuid())) {
                        chain.setReturnPointOverride(beyond);
                    }
                }
                InstancesPlugin.exitInstance(ref, store);
                return Outcome.SENT;
            }
            Transform here = standingAt(ref, store);
            World live = key == null ? null : Universe.get().getWorld(key);
            if (live != null) {
                InstancesPlugin.teleportPlayerToInstance(ref, store, live, here);
                return Outcome.SENT;
            }
            InstancesPlugin plugin = InstancesPlugin.get();
            if (plugin == null) {
                return Outcome.FAILED;
            }
            CompletableFuture<World> copy = plugin.spawnInstance(template, key, world, here);
            InstancesPlugin.teleportPlayerToLoadingInstance(ref, store, copy, here);
            return Outcome.SENT;
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a player could not be sent to the instance " + template, t);
            return Outcome.FAILED;
        }
    }

    /**
     * Send the player out of every instance at once: to the first world in their return chain that is no instance,
     * where they stood when they left it, else the default world's spawn.
     */
    @Nonnull
    public static Outcome toOverworld(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || busy(ref, store, player)) {
            return Outcome.BUSY;
        }
        World world = store.getExternalData().getWorld();
        if (InstanceWorldConfig.get(world.getWorldConfig()) == null) {
            return Outcome.ALREADY_THERE;
        }
        try {
            Universe universe = Universe.get();
            InstanceEntityConfig chain = store.getComponent(ref, InstanceEntityConfig.getComponentType());
            WorldReturnPoint point = chain == null ? null : chain.getReturnPoint();
            while (point != null) {
                World target = universe.getWorld(point.getWorld());
                if (target != null && InstanceWorldConfig.get(target.getWorldConfig()) == null) {
                    store.addComponent(ref, Teleport.getComponentType(),
                            Teleport.createForPlayer(target, new Transform(point.getReturnPoint())));
                    return Outcome.SENT;
                }
                point = chain.popReturnPoint();
            }
            return toDefaultSpawn(ref, store, world);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a player could not be sent out to the overworld", t);
            return Outcome.FAILED;
        }
    }

    /** Is {@code world} a copy of {@code template}, or the world living under {@code key}? */
    public static boolean isCopyOf(@Nullable World world, @Nonnull String template, @Nullable String key) {
        if (world == null) {
            return false;
        }
        if (key != null && key.equalsIgnoreCase(world.getName())) {
            return true;
        }
        InstanceWorldConfig config = InstanceWorldConfig.get(world.getWorldConfig());
        return config != null && template.equalsIgnoreCase(config.getInstanceName());
    }

    /** Does a loaded pack ship the instance {@code template}? A name the engine refuses reads as no. */
    public static boolean templateExists(@Nonnull String template) {
        try {
            return !template.isBlank() && InstancesPlugin.doesInstanceAssetExist(template);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The live copy {@code point} leads into: the world it names while that stands, else the copy now living under the
     * key the point recorded (the template's own), the one the engine's exit reuses; null when none is live. Only the
     * recorded key is read, so this finds exactly the copy the exit will land in.
     */
    @Nullable
    private static World copyHolding(@Nonnull WorldReturnPoint point) {
        Universe universe = Universe.get();
        World named = universe.getWorld(point.getWorld());
        if (named != null) {
            return named;
        }
        String held = point.getInstanceKey();
        return held == null || held.isBlank() ? null : universe.getWorld(held);
    }

    /** Does {@code point} lead into a copy of {@code template}, live or one the engine would respawn at its key? */
    private static boolean leadsInto(@Nonnull WorldReturnPoint point, @Nonnull String template, @Nullable String key) {
        World live = Universe.get().getWorld(point.getWorld());
        if (live != null) {
            return isCopyOf(live, template, key);
        }
        return template.equalsIgnoreCase(point.getInstanceName())
                || (key != null && key.equalsIgnoreCase(point.getInstanceKey()));
    }

    /** The default world's spawn, once its spawn provider answers; the move lands on the player's world thread. */
    @Nonnull
    private static Outcome toDefaultSpawn(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull World from) {
        World home = Universe.get().getDefaultWorld();
        ISpawnProvider spawns = home == null ? null : home.getWorldConfig().getSpawnProvider();
        UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
        if (spawns == null || id == null) {
            return Outcome.FAILED;
        }
        UUID uuid = id.getUuid();
        spawns.getSpawnPointAsync(home, uuid).whenComplete((spawn, error) -> {
            if (error != null || spawn == null) {
                SafeLog.warn(LOG + "the default world's spawn could not be read to send " + uuid + " out", error);
                return;
            }
            from.execute(() -> {
                if (ref.isValid() && store.getComponent(ref, Teleport.getComponentType()) == null) {
                    store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(home, spawn));
                }
            });
        });
        return Outcome.SENT;
    }

    /** Is the player dead, still loading in, or already being moved? */
    private static boolean busy(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player) {
        return player.isWaitingForClientReady()
                || store.getComponent(ref, DeathComponent.getComponentType()) != null
                || store.getComponent(ref, Teleport.getComponentType()) != null
                || store.getComponent(ref, PendingTeleport.getComponentType()) != null;
    }

    /** Where the player stands now, facing as they face. */
    @Nonnull
    private static Transform standingAt(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        TransformComponent at = store.getComponent(ref, TransformComponent.getComponentType());
        return at == null ? new Transform() : new Transform(at.getTransform());
    }
}
