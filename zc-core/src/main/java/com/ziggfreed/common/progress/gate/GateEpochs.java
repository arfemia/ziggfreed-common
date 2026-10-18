package com.ziggfreed.common.progress.gate;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.permissions.GroupPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.permissions.PlayerPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.util.SafeLog;

/**
 * The clocks a kept gate verdict is checked against ({@link CompiledGate}): one server epoch, and one epoch per
 * online player. A verdict over {@code Server} readings holds while the server epoch stands; one over
 * {@code Player} readings holds while both the server epoch and that player's epoch stand. What moves a
 * player's epoch: their own progression moments (zc-progression's listener), a quest's completed status moving
 * either way outside a moment (zc-progression's {@code QuestEngine}: finished, re-armed, abandoned or wiped), a
 * move of one of their track readings (zc-track: a level, a best level or a prestige count; a move inside one
 * level moves nothing), a permission change, and a world change, twice (the player added to a world, which
 * fires before they stand in the new world's store, and again once they are ready in it). What moves the server
 * epoch: a group's permission change, a Factors reload (zc-core's {@code DerivedFactorConfig}) and a quest
 * catalogue publish; a vocabulary change also reaches every verdict through {@code FactorRegistry.generation()},
 * which a verdict carries. A module whose own content reload changes a {@code Player} reading for everyone (a
 * track's curve) moves the server epoch itself.
 *
 * <p>Once the server has booted, a move of a player's epoch or of the server epoch fires
 * {@link ZigGateEpochMovedEvent}, whether or not a verdict was kept for that player, so a gate asked only at a
 * moment of its own choosing can ask again; but only while some listener has said it wants the moves
 * ({@link #announceWhile}): a listener that cares only about some players (a gear set's gated wearers) says so
 * with a cheap test, and a move nobody wants allocates nothing.
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

    /**
     * Who wants the moves announced, each answering a cheap "right now?" ({@link #announceWhile}). A plain array
     * replaced whole on each registration, so the walk on every move allocates nothing (a list's iterator would).
     */
    private static volatile BooleanSupplier[] wantedTests = new BooleanSupplier[0];

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
     * Move {@code playerId}'s epoch: every verdict kept for them over {@code Player} readings is read again, and,
     * while a listener wants moves ({@link #announceWhile}), {@link ZigGateEpochMovedEvent} fires for them even
     * when nothing was kept.
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

    /**
     * Announce the moves ({@link ZigGateEpochMovedEvent}) while {@code test} answers true: a listener's cheap
     * test of whether it has anyone to re-check right now (a gear set's gated wearers). Asked on every move, on
     * the moving thread, so it must not block; a test that throws counts as not wanting. Once, at setup, beside
     * the listener's own registration on the bus: without it the listener never hears a move.
     */
    public static synchronized void announceWhile(@Nonnull BooleanSupplier test) {
        BooleanSupplier[] now = wantedTests;
        BooleanSupplier[] next = Arrays.copyOf(now, now.length + 1);
        next[now.length] = test;
        wantedTests = next;
    }

    /** Fire {@link ZigGateEpochMovedEvent} for {@code playerId} (null: the server epoch), once booted and wanted. */
    private static void announce(@Nullable UUID playerId) {
        if (keeping && wanted()) {
            EVENTS.fire("ZigGateEpochMoved", ZigGateEpochMovedEvent.class, () -> new ZigGateEpochMovedEvent(playerId));
        }
    }

    /** Does any listener want a move announced right now? Asked on every move, so it allocates nothing. */
    private static boolean wanted() {
        BooleanSupplier[] tests = wantedTests;
        for (int i = 0; i < tests.length; i++) {
            BooleanSupplier test = tests[i];
            try {
                if (test.getAsBoolean()) {
                    return true;
                }
            } catch (Throwable t) {
                SafeLog.fine("[gate] an epoch listener's test failed: " + t.getMessage());
            }
        }
        return false;
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

    /**
     * A player was added to a world: a first join, a world change or an instance entry alike. It fires before the
     * player stands in the new world's store, so a re-check it starts may still read the old world;
     * {@link #onPlayerReady} moves the epoch again once they are in.
     */
    public static void onWorldChange(@Nonnull AddPlayerToWorldEvent event) {
        try {
            PlayerRef ref = event.getHolder().getComponent(PlayerRef.getComponentType());
            bumpPlayer(ref == null ? null : ref.getUuid());
        } catch (Throwable t) {
            SafeLog.fine("[gate] could not move a player's epoch on a world change: " + t.getMessage());
        }
    }

    /** A player is ready in a world (after every join and world change): what they read now is the new world's. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Ref<EntityStore> ref = event.getPlayerRef();
            PlayerRef player = ref == null || !ref.isValid() ? null
                    : ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            bumpPlayer(player == null ? null : player.getUuid());
        } catch (Throwable t) {
            SafeLog.fine("[gate] could not move a player's epoch once they were ready: " + t.getMessage());
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
