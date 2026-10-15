package com.ziggfreed.common.ui.hud;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.portals.PortalsPlugin;
import com.hypixel.hytale.builtin.portals.resources.PortalWorld;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.util.SafeLog;

/**
 * The game's own Portal panel: the band the client draws at the top right of the screen while a
 * player stands in a portal world (the Deepworks, a portal fragment's world), naming the portal and,
 * for a timed one, its timer and countdown bar. It is a native HUD component
 * ({@link HudComponent#PortalPanel}) the client draws itself (its {@code Data/Shared/UI/Hud/PortalPanel.xaml})
 * from what the engine's Portals plugin sends every player added to a world whose {@link PortalWorld}
 * resource exists, so a custom HUD pinned to the same corner sits inside it. The client moves nothing
 * aside for it, and its anchor is in the same units as a custom HUD's.
 *
 * <p>{@link #clearanceFor} says how far down the panel reaches on a player's screen right now, and
 * {@link #below} moves a top-right position clear of it; a HUD folds the two into its
 * {@code configuredPosition()}. The engine fires {@code PlayerReadyEvent} once for every world a player
 * comes into, so a HUD built at that event is placed for the world it is in, the portal world and the one
 * they return to alike.
 */
public final class PortalPanel {

    /** Where the client pins the panel's top: 80 down from the top of the screen, 2 in from the right. */
    private static final int PANEL_TOP = 80;

    /** The header art's height, which is all an untimed portal (the Deepworks) shows: its name and "PORTAL". */
    private static final int HEADER_HEIGHT = 86;

    /** The whole view's height, which a timed portal fills with its timer line, countdown bar and scrim. */
    private static final int VIEW_HEIGHT = 133;

    /** Room left between the panel and a HUD stepped below it. */
    private static final int GAP = 14;

    /** How far down the panel reaches with only the portal's name showing. */
    static final int NAME_ONLY_CLEARANCE = PANEL_TOP + HEADER_HEIGHT + GAP;

    /** How far down it reaches with a timer showing too. */
    static final int TIMED_CLEARANCE = PANEL_TOP + VIEW_HEIGHT + GAP;

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private PortalPanel() {
    }

    /**
     * How far down from the top the Portal panel reaches on {@code playerRef}'s screen right now, or 0
     * when it is not there: they are not in a portal world, the server or a command hid the component,
     * the Portals plugin is not running, or the player is gone. World thread (it reads the player's
     * store). Never throws. A HUD reads it when it builds or repositions, so a player who toggles the
     * panel inside a portal world (the engine's portal {@code toggleui} command) sees the HUD follow at
     * their next world change or reposition.
     */
    public static int clearanceFor(@Nonnull PlayerRef playerRef) {
        try {
            // The resource type is the Portals plugin's own, and asking for it with that plugin absent throws.
            if (PortalsPlugin.getInstance() == null) {
                return 0;
            }
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return 0;
            }
            Store<EntityStore> store = ref.getStore();
            PortalWorld portal = store.getResource(PortalWorld.getResourceType());
            if (portal == null || !portal.exists()) {
                return 0;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || !player.getHudManager().getVisibleHudComponents().contains(HudComponent.PortalPanel)) {
                return 0;
            }
            return portal.hasTimeLimit() ? TIMED_CLEARANCE : NAME_ONLY_CLEARANCE;
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                SafeLog.warn("[hud] the game's Portal panel could not be read, so no HUD steps around it: "
                        + t.getMessage());
            }
            return 0;
        }
    }

    /**
     * {@code position} moved down to start at {@code clearance} when it is pinned to the top right and
     * starts above it; any other position, a position already below it, and a clearance of 0 come back
     * unchanged. Pure.
     */
    @Nonnull
    public static HudPosition below(@Nonnull HudPosition position, int clearance) {
        if (clearance <= 0
                || position.getAnchorEdge() != HudPosition.AnchorEdge.TOP
                || position.getHorizontalEdge() != HudPosition.HorizontalEdge.RIGHT
                || position.getOffsetY() >= clearance) {
            return position;
        }
        return new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.RIGHT,
                position.getOffsetX(), clearance);
    }
}
