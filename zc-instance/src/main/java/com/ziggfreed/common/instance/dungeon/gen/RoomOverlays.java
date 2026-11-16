package com.ziggfreed.common.instance.dungeon.gen;

import com.ziggfreed.common.instance.dungeon.layout.RoomSpec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one place the painter asks for a room's overlay: a second prefab of blocks (and the room's logic entities)
 * painted on top of the room's base prefab, at the same anchor and turn, both clipped to the room's {@code Box}. It
 * reads the room file's {@code Overlay} leaf, a prefab in the engine's dot form under {@code Server/Prefabs/} (one
 * prefab, so positions hold against the base); the overlay's entities are the room logic's, never the painter's.
 */
final class RoomOverlays {

    private RoomOverlays() {
    }

    /**
     * The overlay prefab {@code room} names in its room file's {@code Overlay} leaf ({@link RoomSpec#overlay}), in dot
     * form, or null for none. The catalogue audit's {@code OVERLAY_POOL} already refuses an overlay that names a pool or
     * sits over one, so a name here is one prefab.
     */
    @Nullable
    static String overlayOf(@Nonnull RoomSpec room) {
        return room.overlay();
    }
}
