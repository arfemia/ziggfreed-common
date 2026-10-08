package com.ziggfreed.common.worldmap;

/**
 * Where a viewer stands on the map, as the engine's map tracker last read it: only the two axes a
 * map and a compass use. A resolver that points at the nearest of several copies of a place reads it.
 */
public record WaypointViewer(double x, double z) {
}
