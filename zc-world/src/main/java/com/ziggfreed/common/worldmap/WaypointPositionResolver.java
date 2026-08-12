package com.ziggfreed.common.worldmap;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * Turns a {@link WaypointTarget#positionKey()} into the coordinates it means IN ONE WORLD.
 *
 * <p>Deliberately the only thing between a target and a marker, so a consumer that already keeps a
 * lookup of where things stand plugs it in and needs nothing else here: the resolver's whole
 * contract is a key plus a world name in, zero or more positions out. A key that resolves nowhere in
 * this world returns an empty list, which is how a marker for a place that is not in this world (or
 * is not placed anywhere yet) simply does not appear.
 *
 * <p><b>Called OFF the world thread</b>, on the map tracker, once per viewer per update. It must
 * therefore read only from something safe to read there - a concurrent cache kept current elsewhere -
 * and must never touch the entity store. Keep it allocation-light; it is on a repeating path.
 */
@FunctionalInterface
public interface WaypointPositionResolver {

    /** Resolves nothing anywhere. */
    WaypointPositionResolver NONE = (worldName, positionKey) -> List.of();

    /** Every position {@code positionKey} means in {@code worldName}; empty when it means none. */
    @Nonnull
    List<WaypointPosition> resolve(@Nonnull String worldName, @Nonnull String positionKey);
}
