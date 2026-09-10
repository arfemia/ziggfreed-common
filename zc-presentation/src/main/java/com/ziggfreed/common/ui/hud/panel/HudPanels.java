package com.ziggfreed.common.ui.hud.panel;

import java.util.Comparator;
import java.util.List;
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
import com.ziggfreed.common.ui.hud.HudPreferences;
import com.ziggfreed.common.ui.hud.KeyedCustomHud;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to the shared progress-bar panels: their lifecycle on every player, and the calls a
 * consumer makes when a value it owns has moved.
 *
 * <p><b>Attach and detach.</b> Every player gets BOTH panels at ready and loses them at disconnect:
 * {@link LedgerPanelHud}, the Activity ledger, and {@link WorldPanelHud}, the World bars. They are
 * attached LATE on the ready event so they land after whatever a consumer does there, and kept in
 * {@link #LIVE} by player uuid so a change reported from any thread finds the right one in two
 * map reads. Where each sits for a given player is resolved per paint
 * ({@link HudSpot}), and a player changing their own pick or hiding a panel
 * ({@link HudPreferences}) repaints theirs through the watcher this class registers at install.
 *
 * <p><b>Which panel a row lands on is the CALLER's choice, made by which call it makes</b>
 * ({@link #moved} or {@link #movedInWorld}), never by anything this class reads out of the row. Only
 * the mod reporting a movement knows what the player is in the middle of, and that is the whole
 * basis of the choice.
 *
 * <p><b>Rows come from the calls, not from files.</b> {@link #moved} names a row id, a delta, where
 * the value now stands ({@link HudBarReading}) and how the row should look ({@link HudRowDisplay});
 * the panel creates the row on its first move, dresses it from that display, keeps the reading
 * and records the gain, and paints the fill from what the row holds. Nothing is asked of anyone at
 * paint time and nothing is registered anywhere: a row exists only because something moved, and
 * whoever moved it already knew where the value stood. {@link #itemMoved} names an item and a
 * count and nothing more: the row's name and picture are the item's own, its number is the
 * running count, and it draws no fill. Both rows share one panel and one {@code MaxVisible}, fill
 * rows above item rows. A {@link HudRowAsset} authored for a row is an OPTIONAL override over
 * either, and one switched off keeps the row off the panel.
 *
 * <p><b>A row id is opaque.</b> The panel reads nothing into it, splits nothing off it and matches
 * it only against an override's {@code Source}: the mod moving a value names its row by the id of
 * the thing measured, nothing more. The one id shape this class owns is the item row's
 * {@value #ITEM_ROW_PREFIX} prefix, its own keying for a row it dresses itself.
 *
 * <p>Owner-wide changes go through {@link #repaintAllOnline()}, called from the three asset load
 * events and every owner-file write, so a reload lands on every screen without a reconnect; a
 * repaint re-anchors as it draws, so there is no separate position push.
 */
public final class HudPanels {

    /**
     * Every live panel by player uuid, then by panel key: written at attach, dropped at detach, read
     * by every change. A player has one of each panel, and which one a movement lands on is decided
     * by the call the reporting mod makes, never by anything read out of the row.
     */
    static final Map<UUID, Map<String, HudPanelHud>> LIVE = new ConcurrentHashMap<>();

    /**
     * The two panels every player carries, in ATTACH order: the client draws a later document over
     * an earlier one, so this order decides which panel wins where two overlap on screen, and it
     * never follows an authored leaf. Where a settings page lists them is {@link #listing}.
     */
    private static final List<HudPanelLayout> PANELS = List.of(LedgerPanelHud.LAYOUT, WorldPanelHud.LAYOUT);

    private HudPanels() {
    }

    // ==================== install ====================

    /** Register the lifecycle. Call once from setup. Guarded and loud, like every HUD install in this library. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            var events = plugin.getEventRegistry();
            events.registerGlobal(EventPriority.LATE, PlayerReadyEvent.class, HudPanels::onPlayerReady);
            events.register(PlayerDisconnectEvent.class, HudPanels::onPlayerDisconnect);
            HudPreferences.watch(HudPanels::repaintFor);
            SafeLog.info("[hud] progress-bar panels installed: attach at player ready, paint on reported"
                    + " value changes (no tick), follow each player's own placement picks");
        } catch (Throwable t) {
            SafeLog.warn("[hud] the progress-bar panels could not be installed; no bar will appear this boot", t);
        }
    }

    /** The panels every player carries, in attach order: what a command resolves a panel id against. */
    @Nonnull
    public static List<HudPanelLayout> panels() {
        return PANELS;
    }

    /**
     * The panel ids in the order a settings page lists them: by each panel's folded {@code Order},
     * lower first, then by id. Read per open, so an owner's reorder lands on the next open. The
     * ONE list both tabs read, and NOT the attach order ({@link #panels}), which decides what draws
     * over what and stays fixed whatever a file says.
     */
    @Nonnull
    public static List<String> listing() {
        return listing(HudPanelConfig.getInstance());
    }

    /** As {@link #listing()}, over an explicit fold, for a test. */
    @Nonnull
    static List<String> listing(@Nonnull HudPanelConfig panels) {
        return PANELS.stream()
                .map(HudPanelLayout::panelId)
                .sorted(Comparator.comparingInt((String id) -> panels.panel(id).order())
                        .thenComparing(id -> id, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** The panel whose id is {@code panelId} (any case), or null when no panel has it. */
    @Nullable
    public static HudPanelLayout panel(@Nullable String panelId) {
        if (panelId == null) {
            return null;
        }
        for (HudPanelLayout layout : PANELS) {
            if (layout.panelId().equalsIgnoreCase(panelId.trim())) {
                return layout;
            }
        }
        return null;
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
            @Nonnull HudBarReading reading, @Nonnull HudRowDisplay display) {
        return report(playerRef, LedgerPanelHud.HUD_KEY, rowId, reading, null, delta, display, false);
    }

    /**
     * As {@link #moved}, on the World bars instead: the wide panel for values that move while a
     * player is out in the world with no ledger beside them. Same row model and same display; only which
     * panel draws it differs, and that is the caller's to decide because only the caller knows what
     * the player is in the middle of.
     */
    public static boolean movedInWorld(@Nullable PlayerRef playerRef, @Nonnull String rowId, double delta,
            @Nonnull HudBarReading reading, @Nonnull HudRowDisplay display) {
        return report(playerRef, WorldPanelHud.HUD_KEY, rowId, reading, null, delta, display, false);
    }

    /**
     * {@code quantity} of {@code itemId} landed for {@code playerRef}: count it on the item's own
     * row ({@code item:<ItemId>}), created on first sight and dressed by the item itself
     * ({@link HudRowDisplay#forItem}). Returns whether a row took it, as {@link #moved} does.
     */
    public static boolean itemMoved(@Nullable PlayerRef playerRef, @Nonnull String itemId, double quantity) {
        return report(playerRef, LedgerPanelHud.HUD_KEY, itemRowId(itemId), null, itemId, quantity,
                HudRowDisplay.forItem(itemId), false);
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
            @Nonnull String itemId, double quantity, @Nonnull HudRowDisplay display) {
        return report(playerRef, LedgerPanelHud.HUD_KEY, rowId, null, itemId, quantity,
                display.over(HudRowDisplay.forItem(itemId)), false);
    }

    /** The id of the row {@code itemId}'s output is counted on. */
    @Nonnull
    public static String itemRowId(@Nonnull String itemId) {
        return ITEM_ROW_PREFIX + itemId;
    }

    /**
     * As {@link #moved}, but {@code total} is the row's number OUTRIGHT rather than something to add
     * to it: for a mod that already keeps its own running total for the stretch of activity the row
     * belongs to. Stating the total means the row and whatever else that mod shows the same total on
     * cannot drift apart, and a row that went away and came back still reads right.
     */
    public static boolean totalled(@Nullable PlayerRef playerRef, @Nonnull String rowId, double total,
            @Nonnull HudBarReading reading, @Nonnull HudRowDisplay display) {
        return report(playerRef, LedgerPanelHud.HUD_KEY, rowId, reading, null, total, display, true);
    }

    /**
     * As {@link #itemMoved}'s caller-named form, with {@code total} the row's number outright rather
     * than something to add to it. See {@link #totalled}.
     */
    public static boolean itemTotalled(@Nullable PlayerRef playerRef, @Nonnull String rowId,
            @Nonnull String itemId, double total, @Nonnull HudRowDisplay display) {
        return report(playerRef, LedgerPanelHud.HUD_KEY, rowId, null, itemId, total,
                display.over(HudRowDisplay.forItem(itemId)), true);
    }

    /**
     * Send every row {@code playerRef} currently has on either panel away within {@code withinMs},
     * whatever each was going to do on its own: what ends a set of rows held through a stretch of
     * activity ({@link HudRowLook#LINGER_HELD}) when that activity finishes. A row already fading
     * sooner keeps its own time, and a player with no panel is a no-op.
     */
    public static void fadeAll(@Nullable PlayerRef playerRef, long withinMs) {
        for (HudPanelHud hud : panelsOf(playerRef)) {
            hud.fadeAll(withinMs);
        }
    }

    private static boolean report(@Nullable PlayerRef playerRef, @Nonnull String hudKey, @Nonnull String rowId,
            @Nullable HudBarReading reading, @Nullable String itemId, double delta,
            @Nonnull HudRowDisplay display, boolean absolute) {
        if (playerRef == null) {
            return false;
        }
        UUID uuid = playerRef.getUuid();
        Map<String, HudPanelHud> panels = uuid == null ? null : LIVE.get(uuid);
        HudPanelHud hud = panels == null ? null : panels.get(hudKey);
        if (hud == null) {
            return false;
        }
        HudRowAsset override = HudRowConfig.getInstance().bySource(rowId);
        if (override != null && !override.enabled()) {
            return false;
        }
        hud.moved(rowId, reading, itemId, delta, display, absolute);
        return true;
    }

    // ==================== repaints ====================

    /** Repaint every online player's panels: the bars, a panel or a spot were reloaded or rewritten. */
    public static void repaintAllOnline() {
        for (Map<String, HudPanelHud> panels : LIVE.values()) {
            for (HudPanelHud hud : panels.values()) {
                hud.repaint();
            }
        }
    }

    /** Repaint one player's panels: their own pick or hide changed. Any thread. */
    public static void repaintFor(@Nullable PlayerRef playerRef) {
        for (HudPanelHud hud : panelsOf(playerRef)) {
            hud.repaint();
        }
    }

    @Nonnull
    private static Iterable<HudPanelHud> panelsOf(@Nullable PlayerRef playerRef) {
        UUID uuid = playerRef == null ? null : playerRef.getUuid();
        Map<String, HudPanelHud> panels = uuid == null ? null : LIVE.get(uuid);
        return panels == null ? List.of() : panels.values();
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
            HudPanelHud stack = new LedgerPanelHud(playerRef);
            HudPanelHud grid = new WorldPanelHud(playerRef);
            LIVE.put(uuid, Map.of(LedgerPanelHud.HUD_KEY, stack, WorldPanelHud.HUD_KEY, grid));
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
                player.getHudManager().removeCustomHud(playerRef, LedgerPanelHud.HUD_KEY);
                player.getHudManager().removeCustomHud(playerRef, WorldPanelHud.HUD_KEY);
            }
        } catch (Throwable t) {
            SafeLog.warn("[hud] progress-bar panel detach failed on the world thread", t);
        }
    }

    // ==================== registry, for a test ====================

    /** Remember {@code hud} as {@code playerId}'s panel of its kind; replaces a stale one from a reconnect. */
    static void register(@Nonnull UUID playerId, @Nonnull HudPanelHud hud) {
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
