package com.ziggfreed.common.ui.hud.bar;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
import com.ziggfreed.common.ui.hud.KeyedCustomHud;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to the shared progress-bar panel: its lifecycle on every player, and the two calls a
 * consumer makes when a value it owns has moved.
 *
 * <p><b>Attach and detach.</b> Every player gets BOTH panels at ready and loses them at disconnect:
 * {@link HudBarStackHud}, the tall ledger in the left column, and {@link HudBarGridHud}, the wide
 * block in the top-right. They are attached LATE on the ready event so they land after whatever a
 * consumer does there, and kept in {@link #LIVE} by player uuid so a change reported from any
 * thread finds the right one in two map reads.
 *
 * <p><b>Which panel a row lands on is the CALLER's choice, made by which call it makes</b>
 * ({@link #moved} or {@link #movedOnGrid}), never by anything this class reads out of the row. Only
 * the mod reporting a movement knows what the player is in the middle of, and that is the whole
 * basis of the choice.
 *
 * <p><b>Rows come from the calls, not from files.</b> {@link #moved} names a row id, a delta, where
 * the value now stands ({@link HudBarReading}) and how the row should look ({@link HudBarDisplay});
 * the panel creates the row on its first move, dresses it from that display, keeps the reading
 * and records the gain, and paints the fill from what the row holds. Nothing is asked of anyone at
 * paint time and nothing is registered anywhere: a row exists only because something moved, and
 * whoever moved it already knew where the value stood. {@link #itemMoved} names an item and a
 * count and nothing more: the row's name and picture are the item's own, its number is the
 * running count, and it draws no fill. Both rows share one panel and one {@code MaxVisible}, fill
 * rows above item rows. A {@link HudBarAsset} authored for a row is an OPTIONAL override over
 * either, and one switched off keeps the row off the panel.
 *
 * <p><b>A row id is opaque.</b> The panel reads nothing into it, splits nothing off it and matches
 * it only against an override's {@code Source}: the mod moving a value names its row by the id of
 * the thing measured, nothing more. The one id shape this class owns is the item row's
 * {@value #ITEM_ROW_PREFIX} prefix, its own keying for a row it dresses itself.
 *
 * <p>Owner-wide changes go through {@link #repaintAllOnline()} and
 * {@link #refreshPositionForAllOnline()}; both are called from the asset load events, so a reload
 * lands on every screen without a reconnect.
 */
public final class HudBars {

    /**
     * Every live panel by player uuid, then by panel id: written at attach, dropped at detach, read
     * by every change. A player has one of each panel, and which one a movement lands on is decided
     * by the call the reporting mod makes, never by anything read out of the row.
     */
    static final Map<UUID, Map<String, HudBarHud>> LIVE = new ConcurrentHashMap<>();

    private HudBars() {
    }

    // ==================== install ====================

    /** Register the lifecycle. Call once from setup. Guarded and loud, like every HUD install in this library. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            var events = plugin.getEventRegistry();
            events.registerGlobal(EventPriority.LATE, PlayerReadyEvent.class, HudBars::onPlayerReady);
            events.register(PlayerDisconnectEvent.class, HudBars::onPlayerDisconnect);
            SafeLog.info("[hud] progress-bar panel installed: attaches at player ready, paints on reported"
                    + " value changes (no tick)");
        } catch (Throwable t) {
            SafeLog.warn("[hud] the progress-bar panel could not be installed; no bar will appear this boot", t);
        }
    }

    // ==================== the two calls ====================

    /** The prefix of the row an item's output is counted on: {@code item:<ItemId>}. */
    public static final String ITEM_ROW_PREFIX = "item:";

    /**
     * The value on row {@code rowId} moved by {@code delta} for {@code playerRef}, now stands at
     * {@code reading}, and this is how its row should look. Returns whether a row took it: false
     * for a player with no panel, a row an override switched off, or a null reference. Any thread;
     * the paint runs on the player's world thread.
     */
    public static boolean moved(@Nullable PlayerRef playerRef, @Nonnull String rowId, double delta,
            @Nonnull HudBarReading reading, @Nonnull HudBarDisplay display) {
        return report(playerRef, HudBarStackHud.HUD_KEY, rowId, reading, null, delta, display);
    }

    /**
     * As {@link #moved}, on the WIDE panel instead: the one for values that move while a player is
     * out in the world with no ledger beside them. Same row model and same display; only which
     * panel draws it differs, and that is the caller's to decide because only the caller knows what
     * the player is in the middle of.
     */
    public static boolean movedOnGrid(@Nullable PlayerRef playerRef, @Nonnull String rowId, double delta,
            @Nonnull HudBarReading reading, @Nonnull HudBarDisplay display) {
        return report(playerRef, HudBarGridHud.HUD_KEY, rowId, reading, null, delta, display);
    }

    /**
     * {@code quantity} of {@code itemId} landed for {@code playerRef}: count it on the item's own
     * row ({@code item:<ItemId>}), created on first sight and dressed by the item itself
     * ({@link HudBarDisplay#forItem}). Returns whether a row took it, as {@link #moved} does.
     */
    public static boolean itemMoved(@Nullable PlayerRef playerRef, @Nonnull String itemId, double quantity) {
        return report(playerRef, HudBarStackHud.HUD_KEY, itemRowId(itemId), null, itemId, quantity,
                HudBarDisplay.forItem(itemId));
    }

    /**
     * As {@link #itemMoved}, but on a row the CALLER names and dresses: for an item that landed in a
     * way worth counting apart from the ordinary running total of the same item. The row still
     * pictures and names the item itself, so {@code display} only has to say what is different about
     * it; anything it leaves out falls back to the item's own display, then to an authored override,
     * then to the defaults, exactly as every other row does.
     *
     * <p>The row id is the caller's, matched whole against an override's {@code Source} like any
     * other, so a mod names such a row after whatever made it special in its own vocabulary. Passing
     * {@link #itemRowId} here is the same thing as calling {@link #itemMoved}.
     */
    public static boolean itemMoved(@Nullable PlayerRef playerRef, @Nonnull String rowId,
            @Nonnull String itemId, double quantity, @Nonnull HudBarDisplay display) {
        return report(playerRef, HudBarStackHud.HUD_KEY, rowId, null, itemId, quantity,
                display.over(HudBarDisplay.forItem(itemId)));
    }

    /** The id of the row {@code itemId}'s output is counted on. */
    @Nonnull
    public static String itemRowId(@Nonnull String itemId) {
        return ITEM_ROW_PREFIX + itemId;
    }

    private static boolean report(@Nullable PlayerRef playerRef, @Nonnull String hudKey, @Nonnull String rowId,
            @Nullable HudBarReading reading, @Nullable String itemId, double delta, @Nonnull HudBarDisplay display) {
        if (playerRef == null) {
            return false;
        }
        UUID uuid = playerRef.getUuid();
        Map<String, HudBarHud> panels = uuid == null ? null : LIVE.get(uuid);
        HudBarHud hud = panels == null ? null : panels.get(hudKey);
        if (hud == null) {
            return false;
        }
        HudBarAsset override = HudBarConfig.getInstance().bySource(rowId);
        if (override != null && !override.enabled()) {
            return false;
        }
        hud.moved(rowId, reading, itemId, delta, display);
        return true;
    }

    // ==================== owner-wide pushes ====================

    /** Repaint every online player's panels: the bars or a panel were reloaded. */
    public static void repaintAllOnline() {
        for (Map<String, HudBarHud> panels : LIVE.values()) {
            for (HudBarHud hud : panels.values()) {
                hud.repaint();
            }
        }
    }

    /** Re-anchor every online player's panels to their folded positions: the owner moved one. */
    public static void refreshPositionForAllOnline() {
        HudBarPanelConfig config = HudBarPanelConfig.getInstance();
        KeyedCustomHud.refreshPositionForAllOnline(HudBarStackHud.HUD_KEY,
                config.current().position(HudBarStackHud.LAYOUT.defaultPosition()));
        KeyedCustomHud.refreshPositionForAllOnline(HudBarGridHud.HUD_KEY,
                config.grid().position(HudBarGridHud.LAYOUT.defaultPosition()));
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
            SafeLog.warn("[hud] progress-bar panel attach failed", t);
        }
    }

    /** World thread: build the panel, remember it by uuid, hand it to the native {@code HudManager}. */
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
            HudBarHud stack = new HudBarStackHud(playerRef);
            HudBarHud grid = new HudBarGridHud(playerRef);
            LIVE.put(uuid, Map.of(HudBarStackHud.HUD_KEY, stack, HudBarGridHud.HUD_KEY, grid));
            player.getHudManager().addCustomHud(playerRef, stack);
            player.getHudManager().addCustomHud(playerRef, grid);
        } catch (Throwable t) {
            SafeLog.warn("[hud] progress-bar panel attach failed on the world thread", t);
        }
    }

    private static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef playerRef = event.getPlayerRef();
            UUID uuid = playerRef != null ? playerRef.getUuid() : null;
            if (uuid == null) {
                return;
            }
            LIVE.remove(uuid);
            World world = KeyedCustomHud.aliveWorldOf(playerRef);
            if (world != null) {
                world.execute(() -> detachOnWorldThread(playerRef));
            }
        } catch (Throwable t) {
            SafeLog.warn("[hud] progress-bar panel detach failed", t);
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
                player.getHudManager().removeCustomHud(playerRef, HudBarStackHud.HUD_KEY);
                player.getHudManager().removeCustomHud(playerRef, HudBarGridHud.HUD_KEY);
            }
        } catch (Throwable t) {
            SafeLog.warn("[hud] progress-bar panel detach failed on the world thread", t);
        }
    }

    // ==================== registry, for a test ====================

    /** Remember {@code hud} as {@code playerId}'s panel of its kind; replaces a stale one from a reconnect. */
    static void register(@Nonnull UUID playerId, @Nonnull HudBarHud hud) {
        LIVE.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>())
                .put(hud.layout().hudKey(), hud);
    }

    /** Forget {@code playerId}'s panel. */
    static void unregister(@Nonnull UUID playerId) {
        LIVE.remove(playerId);
    }

    /** Whether {@code playerId} has a live panel. */
    static boolean isLive(@Nonnull UUID playerId) {
        return LIVE.containsKey(playerId);
    }
}
