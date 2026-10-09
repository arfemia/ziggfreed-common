package com.ziggfreed.common.objectives.waypoint;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.npc.placement.runtime.PlacementWorlds;
import com.ziggfreed.common.worldmap.Gateways;
import com.ziggfreed.common.worldmap.WaypointPosition;
import com.ziggfreed.common.worldmap.WaypointPositionResolver;
import com.ziggfreed.common.worldmap.WaypointViewer;

/**
 * Turns a quest-marker key into positions in one world, on the map tracker: a character to where its
 * placements stand in this world, a gateway to the nearest of its ways in here.
 */
final class QuestWaypointResolver implements WaypointPositionResolver {

    @Nonnull
    @Override
    public List<WaypointPosition> resolve(@Nonnull String worldName, @Nonnull String positionKey) {
        return resolve(worldName, positionKey, null);
    }

    @Nonnull
    @Override
    public List<WaypointPosition> resolve(@Nonnull String worldName, @Nonnull String positionKey,
            @Nullable WaypointViewer viewer) {
        if (QuestWaypointTargets.isGatewayKey(positionKey)) {
            String gatewayId = positionKey.substring(QuestWaypointTargets.GATEWAY_PREFIX.length());
            return Gateways.nearest(Gateways.positions(worldName, gatewayId), viewer);
        }
        return PlacementWorlds.positionsOf(worldName, positionKey);
    }
}
