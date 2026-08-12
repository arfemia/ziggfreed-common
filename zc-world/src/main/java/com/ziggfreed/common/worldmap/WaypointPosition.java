package com.ziggfreed.common.worldmap;

import javax.annotation.Nonnull;

/**
 * One place a {@link WaypointTarget} resolves to in one world.
 *
 * <p>{@code anchorKey} distinguishes SEVERAL positions the same target resolves to at once, and it
 * is load-bearing rather than decorative: it is what keeps two live copies of the same place from
 * collapsing into one marker, because the marker id is built from the target id and this key
 * together. A resolver that can only ever produce one position per world may pass any constant.
 */
public record WaypointPosition(@Nonnull String anchorKey, double x, double y, double z) {
}
