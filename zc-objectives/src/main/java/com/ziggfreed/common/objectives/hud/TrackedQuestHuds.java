package com.ziggfreed.common.objectives.hud;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.quest.event.QuestAbandonedEvent;
import com.ziggfreed.common.quest.event.QuestAcceptedEvent;
import com.ziggfreed.common.quest.event.QuestClaimedEvent;
import com.ziggfreed.common.quest.event.QuestCompletedEvent;
import com.ziggfreed.common.quest.event.QuestObjectiveProgressedEvent;
import com.ziggfreed.common.quest.event.QuestTrackedEvent;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.KeyedCustomHud;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to {@link TrackedQuestHud}: its lifecycle on every player, the six quest events that
 * repaint it, and the one place a consumer says what it wants the tracker to know.
 *
 * <p><b>Attach and detach.</b> Every player gets a tracker at ready and loses it at disconnect,
 * whoever owns the progression stores: the tracker reads the runtime's own subject, so it shows the
 * right list on a server whose quests belong to a consumer just as it does on a bare one. It is
 * attached LATE on the ready event, after the runtime's own maintenance pass (self-heal,
 * auto-accept) has hopped to the world thread, so the first paint already shows what that pass
 * did. Each live tracker is kept in {@link #LIVE} by player uuid, because every quest event names
 * its player by uuid and nothing else, and looking one up there costs nothing on any thread.
 *
 * <p><b>The events.</b> One repaint per event, coalesced per tick per player; the objective event -
 * the one that fires on ordinary play - is first checked against what the tracker last showed, so a
 * quest the player is not watching costs no paint. Nothing here ticks.
 *
 * <p><b>The two pushes.</b> A player hiding the HUD for themselves and a world rule hiding every
 * HUD are things no quest event announces; the consumer that owns those answers calls
 * {@link #repaint(PlayerRef)} from those two sites. Owner-wide changes (a moved panel, the tracker
 * switched off) go through {@link #refreshPositionForAllOnline} and {@link #repaintAllOnline}.
 *
 * <p><b>Deps resolve lazily.</b> A consumer's layout, audience and theme exist long after this
 * module's setup, so a supplier is registered once and asked on every paint; a consumer that
 * registers nothing gets {@link TrackedQuestHudDeps#DEFAULTS}, which is a working tracker.
 */
public final class TrackedQuestHuds {

    /**
     * One player's tracker as the event side sees it: something to repaint, and something that
     * knows what it last showed. {@link TrackedQuestHud} is the live one; a test registers its own.
     */
    interface Tracker {

        /** Repaint, coalesced, on the player's world thread. Callable from any thread. */
        void repaint();

        /** Is this quest on the tracker as last painted? True when never painted, so a repaint is safe. */
        boolean shows(@Nonnull String questId);
    }

    /** Every live tracker by player uuid: written at attach, dropped at detach, read by every event. */
    static final Map<UUID, Tracker> LIVE = new ConcurrentHashMap<>();

    private static final AtomicReference<Supplier<TrackedQuestHudDeps>> DEPS = new AtomicReference<>();

    private TrackedQuestHuds() {
    }

    // ==================== deps ====================

    /**
     * Say what the tracker should know about a consumer's world. Call once from that consumer's
     * setup; pass null to go back to the library defaults.
     */
    public static void deps(@Nullable Supplier<TrackedQuestHudDeps> supplier) {
        DEPS.set(supplier);
    }

    /**
     * The deps in force right now: the registered consumer's, else the library defaults. Guarded,
     * so a supplier that throws or answers null costs the consumer's own contributions rather than
     * the tracker.
     */
    @Nonnull
    public static TrackedQuestHudDeps resolvedDeps() {
        Supplier<TrackedQuestHudDeps> supplier = DEPS.get();
        if (supplier == null) {
            return TrackedQuestHudDeps.DEFAULTS;
        }
        try {
            TrackedQuestHudDeps deps = supplier.get();
            return deps != null ? deps : TrackedQuestHudDeps.DEFAULTS;
        } catch (Throwable t) {
            SafeLog.warn("[progression] tracked-quest HUD deps failed to resolve: " + t.getMessage());
            return TrackedQuestHudDeps.DEFAULTS;
        }
    }

    // ==================== install ====================

    /**
     * Register the lifecycle and the six repaint subscriptions. Call once from setup. Guarded and
     * LOUD: the engine dispatches an event only when something is listening, so a subscription
     * that silently failed to register would be a tracker that silently never updates, and the one
     * line below is the only place that would show.
     */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            var events = plugin.getEventRegistry();
            events.registerGlobal(EventPriority.LATE, PlayerReadyEvent.class, TrackedQuestHuds::onPlayerReady);
            events.register(PlayerDisconnectEvent.class, TrackedQuestHuds::onPlayerDisconnect);
            events.registerGlobal(QuestTrackedEvent.class, TrackedQuestHuds::onTracked);
            events.registerGlobal(QuestAcceptedEvent.class, TrackedQuestHuds::onAccepted);
            events.registerGlobal(QuestObjectiveProgressedEvent.class, TrackedQuestHuds::onObjectiveProgressed);
            events.registerGlobal(QuestCompletedEvent.class, TrackedQuestHuds::onCompleted);
            events.registerGlobal(QuestClaimedEvent.class, TrackedQuestHuds::onClaimed);
            events.registerGlobal(QuestAbandonedEvent.class, TrackedQuestHuds::onAbandoned);
            SafeLog.info("[progression] tracked-quest HUD installed: attaches at player ready, repaints on"
                    + " QuestTracked, QuestAccepted, QuestObjectiveProgressed, QuestCompleted,"
                    + " QuestClaimed and QuestAbandoned (no tick)");
        } catch (Throwable t) {
            SafeLog.warn("[progression] the tracked-quest HUD could not be installed; the tracker will"
                    + " not appear or will not update this boot", t);
        }
    }

    // ==================== the six events ====================

    static void onTracked(@Nonnull QuestTrackedEvent event) {
        repaint(event.playerId());
    }

    static void onAccepted(@Nonnull QuestAcceptedEvent event) {
        repaint(event.playerId());
    }

    /**
     * The one event that fires on ordinary play, per objective per action. A quest the tracker is
     * not showing costs no paint; one it is showing repaints (coalesced with the rest of the burst).
     */
    static void onObjectiveProgressed(@Nonnull QuestObjectiveProgressedEvent event) {
        Tracker tracker = LIVE.get(event.playerId());
        if (tracker != null && tracker.shows(event.questId())) {
            tracker.repaint();
        }
    }

    static void onCompleted(@Nonnull QuestCompletedEvent event) {
        repaint(event.playerId());
    }

    static void onClaimed(@Nonnull QuestClaimedEvent event) {
        repaint(event.playerId());
    }

    static void onAbandoned(@Nonnull QuestAbandonedEvent event) {
        repaint(event.playerId());
    }

    // ==================== repaint entry points ====================

    /**
     * Repaint {@code playerId}'s tracker, if they have one on. Any thread; the paint itself runs on
     * their world thread. Returns whether a tracker was found.
     */
    public static boolean repaint(@Nonnull UUID playerId) {
        Tracker tracker = LIVE.get(playerId);
        if (tracker == null) {
            return false;
        }
        tracker.repaint();
        return true;
    }

    /** {@link #repaint(UUID)} for a caller holding the reference: the consumer's push path. */
    public static boolean repaint(@Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        return uuid != null && repaint(uuid);
    }

    /** Repaint every online player's tracker: the owner switched it on or off. */
    public static void repaintAllOnline() {
        for (Tracker tracker : LIVE.values()) {
            tracker.repaint();
        }
    }

    /** Re-anchor every online player's tracker live: the owner moved it. */
    public static void refreshPositionForAllOnline(@Nonnull HudPosition position) {
        KeyedCustomHud.refreshPositionForAllOnline(TrackedQuestHud.HUD_KEY, position);
    }

    // ==================== lifecycle ====================

    private static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            World world = player.getWorld();
            if (world == null) {
                return;
            }
            world.execute(() -> attachOnWorldThread(player));
        } catch (Throwable t) {
            SafeLog.warn("[progression] tracked-quest HUD attach failed", t);
        }
    }

    /**
     * World thread: build the tracker, remember it by uuid, and hand it to the native
     * {@code HudManager}, whose {@code addCustomHud} is replace-safe on a reconnect and calls
     * {@code build} - which is the first paint.
     */
    private static void attachOnWorldThread(@Nonnull Player player) {
        try {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            UUID uuid = playerRef != null ? playerRef.getUuid() : null;
            if (uuid == null) {
                return;
            }
            TrackedQuestHud hud = new TrackedQuestHud(playerRef);
            LIVE.put(uuid, hud);
            player.getHudManager().addCustomHud(playerRef, hud);
        } catch (Throwable t) {
            SafeLog.warn("[progression] tracked-quest HUD attach failed on the world thread", t);
        }
    }

    /**
     * Forget the tracker the moment the player goes, then remove the native layer on their world
     * thread. The native manager dies with the entity anyway, so the second half is symmetry.
     */
    private static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef playerRef = event.getPlayerRef();
            UUID uuid = playerRef != null ? playerRef.getUuid() : null;
            if (uuid == null) {
                return;
            }
            LIVE.remove(uuid);
            World world = TrackedQuestHud.worldOf(playerRef);
            if (world != null) {
                world.execute(() -> detachOnWorldThread(playerRef));
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] tracked-quest HUD detach failed", t);
        }
    }

    private static void detachOnWorldThread(@Nonnull PlayerRef playerRef) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            Player player = ref.getStore().getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getHudManager().removeCustomHud(playerRef, TrackedQuestHud.HUD_KEY);
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] tracked-quest HUD detach failed on the world thread", t);
        }
    }

    // ==================== registry, for the lifecycle and for a test ====================

    /** Remember {@code tracker} as {@code playerId}'s; replaces a stale one from a reconnect. */
    static void register(@Nonnull UUID playerId, @Nonnull Tracker tracker) {
        LIVE.put(playerId, tracker);
    }

    /** Forget {@code playerId}'s tracker; nothing repaints for them until the next attach. */
    static void unregister(@Nonnull UUID playerId) {
        LIVE.remove(playerId);
    }

    /** Whether {@code playerId} has a live tracker. */
    static boolean isLive(@Nonnull UUID playerId) {
        return LIVE.containsKey(playerId);
    }
}
