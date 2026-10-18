package com.ziggfreed.common.progress.gate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.permissions.GroupPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.permissions.PlayerPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.util.SafeLog;

/**
 * The clocks a kept gate verdict is checked against ({@link CompiledGate}): one server epoch, and one epoch per
 * online player. A verdict over {@code Server} readings holds while the server epoch stands; one over
 * {@code Player} readings holds while both the server epoch and that player's epoch stand. Anything that can
 * change a {@code Player} reading moves the player's epoch: their own progression moments (zc-progression's
 * listener), a permission change, a world change (the player added to a world); a group's permission change, a
 * Factors reload or a vocabulary change moves the server epoch (the vocabulary through
 * {@code FactorRegistry.generation()}, which a verdict also carries).
 *
 * <p>Once the server has booted, every move of a player's epoch and of the server epoch fires
 * {@link ZigGateEpochMovedEvent}, whether or not a verdict was kept for that player, so a gate asked only at a
 * moment of its own choosing can ask again.
 *
 * <p><b>Nothing is kept before the server has booted</b>: every plugin is still registering readings (a mod
 * table is still filling), so a verdict taken then could outlive what it read. {@link #onBoot} turns keeping
 * on, and moves the server epoch so nothing taken before it survives.
 *
 * <p>A departing player's epoch is forgotten ({@link #onDisconnect}), and every verdict kept against it with
 * it: a gate keeps its player verdicts weakly against the {@link PlayerEpoch} object, never by id.
 */
public final class GateEpochs {

    private static final AtomicLong SERVER = new AtomicLong();

    private static final Map<UUID, PlayerEpoch> PLAYERS = new ConcurrentHashMap<>();

    private static volatile boolean keeping;

    private static final NativeEventSeam EVENTS = new NativeEventSeam("[gate]");

    private GateEpochs() {
    }

    /** One player's epoch: the object a kept verdict is weakly keyed by, and the count it was taken at. */
    public static final class PlayerEpoch {

        private final AtomicLong value = new AtomicLong();

        private PlayerEpoch() {
        }

        /** The count right now. */
        public long value() {
            return value.get();
        }

        void bump() {
            value.incrementAndGet();
        }
    }

    /** The server epoch right now. */
    public static long server() {
        return SERVER.get();
    }

    /** Is a verdict allowed to be kept right now (the server has booted)? */
    public static boolean keeping() {
        return keeping;
    }

    /** Move the server epoch: every kept verdict is read again on its next ask. */
    public static void bumpServer() {
        SERVER.incrementAndGet();
        announce(null);
    }

    /** {@code playerId}'s epoch, created on first ask. */
    @Nonnull
    public static PlayerEpoch player(@Nonnull UUID playerId) {
        return PLAYERS.computeIfAbsent(playerId, id -> new PlayerEpoch());
    }

    /**
     * Move {@code playerId}'s epoch: every verdict kept for them over {@code Player} readings is read again, and
     * {@link ZigGateEpochMovedEvent} fires for them even when nothing was kept.
     */
    public static void bumpPlayer(@Nullable UUID playerId) {
        if (playerId == null) {
            return;
        }
        PlayerEpoch epoch = PLAYERS.get(playerId);
        if (epoch != null) {
            epoch.bump();
        }
        announce(playerId);
    }

    /** Fire {@link ZigGateEpochMovedEvent} for {@code playerId} (null: the server epoch), once booted. */
    private static void announce(@Nullable UUID playerId) {
        if (keeping) {
            EVENTS.fire("ZigGateEpochMoved", ZigGateEpochMovedEvent.class, () -> new ZigGateEpochMovedEvent(playerId));
        }
    }

    /** Forget {@code playerId}: their verdicts go with the epoch object they were kept against. */
    public static void forget(@Nullable UUID playerId) {
        if (playerId != null) {
            PLAYERS.remove(playerId);
        }
    }

    // ==================== the engine's events (registered by GateBootstrap) ====================

    /** The server has booted: keeping starts, past anything a setup-time ask could have seen. */
    public static void onBoot(@Nonnull BootEvent event) {
        bumpServer();
        keeping = true;
    }

    /** A player left. */
    public static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef ref = event.getPlayerRef();
            forget(ref == null ? null : ref.getUuid());
        } catch (Throwable t) {
            SafeLog.fine("[gate] could not forget a departing player's epoch: " + t.getMessage());
        }
    }

    /** A player was added to a world: a first join, a world change or an instance entry alike. */
    public static void onWorldChange(@Nonnull AddPlayerToWorldEvent event) {
        try {
            PlayerRef ref = event.getHolder().getComponent(PlayerRef.getComponentType());
            bumpPlayer(ref == null ? null : ref.getUuid());
        } catch (Throwable t) {
            SafeLog.fine("[gate] could not move a player's epoch on a world change: " + t.getMessage());
        }
    }

    /** One player's permissions or groups changed. */
    public static void onPlayerPermissions(@Nonnull PlayerPermissionChangeEvent event) {
        bumpPlayer(event.getPlayerUuid());
    }

    /** A group's permissions changed, for everyone in it. */
    public static void onGroupPermissions(@Nonnull GroupPermissionChangeEvent event) {
        bumpServer();
    }

    /** Start from nothing, keeping off. Tests only. */
    public static void resetForTests() {
        PLAYERS.clear();
        SERVER.incrementAndGet();
        keeping = false;
    }

    /** Turn keeping on without a boot. Tests only. */
    public static void keepForTests() {
        SERVER.incrementAndGet();
        keeping = true;
    }
}
