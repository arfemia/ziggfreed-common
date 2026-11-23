package com.ziggfreed.common.ui.hud.statbar;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The stat bars on every player: the HUD attached at each player-ready ({@link StatBarHud}, one per player, whatever
 * bars are in force), dropped at disconnect, and painted by {@link StatBarTickSystem}. A mod that spends a pool
 * calls {@link #refresh} after the spend so the bar drops at once rather than on the next paint; nothing else is
 * ever told, because a bar reads its stat itself. Nothing names a stat or a mod here: the bars are files.
 */
public final class StatBars {

    /** Every live stat bar HUD by player uuid: written at attach, dropped at disconnect. Transient, single-process. */
    static final Map<UUID, StatBarHud> LIVE = new ConcurrentHashMap<>();

    private StatBars() {
    }

    /**
     * Paint {@code playerRef}'s bars now, a spend or a refill having moved a pool, rather than on the next tick's
     * paint. World thread. A player with no stat bar HUD is a no-op.
     */
    public static void refresh(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        try {
            Player player = accessor.getComponent(ref, Player.getComponentType());
            PlayerRef playerRef = accessor.getComponent(ref, PlayerRef.getComponentType());
            if (player != null && playerRef != null) {
                paint(accessor, ref, player, playerRef, true);
            }
        } catch (Throwable t) {
            SafeLog.fine("[hud] the stat bars could not be refreshed: " + t.getMessage());
        }
    }

    /** Push every slot whole on every online player's next paint: a bar file, an owner entry or a host HUD moved. */
    public static void repaintAllOnline() {
        for (StatBarHud hud : LIVE.values()) {
            hud.forceRepaint();
        }
    }

    /** One player's paint, due or forced ({@link StatBarHud#paint}). World thread. */
    static void paint(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nonnull PlayerRef playerRef, boolean force) {
        UUID uuid = playerRef.getUuid();
        StatBarHud hud = uuid == null ? null : LIVE.get(uuid);
        if (hud == null) {
            return;
        }
        try {
            hud.paint(accessor, ref, player, force);
        } catch (Throwable t) {
            SafeLog.warn("[hud] the stat bars of " + playerRef.getUsername() + " could not be painted", t);
        }
    }

    /** Attach the player's stat bar HUD on their world's thread. */
    static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            World world = player.getWorld();
            if (world == null) {
                return;
            }
            world.execute(() -> attach(player));
        } catch (Throwable t) {
            SafeLog.warn("[hud] the stat bars could not be attached", t);
        }
    }

    /** Forget the player's stat bar HUD; the engine drops its layer with the player. */
    static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        UUID uuid = playerRef == null ? null : playerRef.getUuid();
        if (uuid != null) {
            LIVE.remove(uuid);
        }
    }

    /** World thread: build the HUD, remember it by uuid, hand it to the native {@code HudManager}. */
    private static void attach(@Nonnull Player player) {
        try {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            UUID uuid = playerRef == null ? null : playerRef.getUuid();
            if (uuid == null) {
                return;
            }
            StatBarHud hud = new StatBarHud(playerRef);
            LIVE.put(uuid, hud);
            player.getHudManager().addCustomHud(playerRef, hud);
        } catch (Throwable t) {
            SafeLog.warn("[hud] the stat bars could not be attached on the world thread", t);
        }
    }
}
