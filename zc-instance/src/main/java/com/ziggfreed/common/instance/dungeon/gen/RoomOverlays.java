package com.ziggfreed.common.instance.dungeon.gen;

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
     * The overlay prefab room {@code roomId} names, in dot form, or null for none. Every room reads none until room
     * files carry the {@code Overlay} leaf.
     */
    @Nullable
    static String overlayOf(@Nonnull String roomId) {
        return null;
    }
}
