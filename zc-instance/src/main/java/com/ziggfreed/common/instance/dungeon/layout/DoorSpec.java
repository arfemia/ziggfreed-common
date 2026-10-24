package com.ziggfreed.common.instance.dungeon.layout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One door socket of a room, in the room's own unrotated frame.
 *
 * @param at     the floor cell at the doorway's centre, just inside the room
 * @param facing the way the door opens out of the room; null when the file names no known facing (the door is
 *               then never joined or capped, and {@link RoomCatalogueAudit} reports it)
 * @param kind   the socket kind, folded; only equal kinds join. A blank kind reads as {@link #DEFAULT_KIND}.
 */
public record DoorSpec(@Nonnull Cell at, @Nullable Facing facing, @Nonnull String kind) {

    /** The kind of a door whose file names none. */
    public static final String DEFAULT_KIND = "default";

    public DoorSpec {
        String folded = Words.fold(kind);
        kind = folded == null ? DEFAULT_KIND : folded;
    }

    /** Whether the door can join or take a cap: it has a facing. */
    public boolean usable() {
        return facing != null;
    }
}
