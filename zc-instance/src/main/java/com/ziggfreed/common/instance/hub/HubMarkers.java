package com.ziggfreed.common.instance.hub;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.worldmap.WorldMapMarkers;

/**
 * A hub copy's map: as the copy starts, each district's marker ({@code Marker} in its file, {@link HubPlan#markers})
 * goes on its map as a native point of interest every player in the copy sees, named by its {@code NameKey}. The
 * markers go with the copy.
 */
public final class HubMarkers {

    private HubMarkers() {
    }

    /** A world started: when it is a hub copy, its districts' markers go on its map. Never throws. */
    public static void onWorldStart(@Nonnull StartWorldEvent event) {
        try {
            World world = event.getWorld();
            ZigHubWorldGenProvider hub = ZigHubWorldGenProvider.of(world);
            if (hub == null) {
                return;
            }
            for (HubPlan.MapSpot spot : hub.plan().markers()) {
                WorldMapMarkers.place(world, spot.id(), spot.x(), spot.y(), spot.z(), spot.icon(),
                        spot.nameKey() == null ? null : Msg.key(spot.nameKey()));
            }
        } catch (Throwable t) {
            SafeLog.warn("[hub] a hub copy's map markers could not be placed", t);
        }
    }
}
