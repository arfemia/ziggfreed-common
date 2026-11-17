package com.ziggfreed.common.instance.dungeon.layout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One door socket of a room, in the room's own unrotated frame.
 *
 * @param at       the floor cell at the doorway's centre, just inside the room
 * @param facing   the way the door opens out of the room; null when the file names no known facing (the door is
 *                 then never joined or capped, and {@link RoomCatalogueAudit} reports it)
 * @param kind     the socket kind, folded; only equal kinds join. A blank kind reads as {@link #DEFAULT_KIND}.
 * @param exitOnly whether the room may only be left by this door: the generator never enters the room through it
 *                 (a puzzle's sealed way on), though the next room, a branch or a cap may join it
 */
public record DoorSpec(@Nonnull Cell at, @Nullable Facing facing, @Nonnull String kind, boolean exitOnly) {

    /** The kind of a door whose file names none. */
    public static final String DEFAULT_KIND = "default";

    public DoorSpec {
        String folded = Words.fold(kind);
        kind = folded == null ? DEFAULT_KIND : folded;
    }

    /** A door the room may be entered and left by. */
    public DoorSpec(@Nonnull Cell at, @Nullable Facing facing, @Nonnull String kind) {
        this(at, facing, kind, false);
    }

    /** Whether the door can join or take a cap: it has a facing. */
    public boolean usable() {
        return facing != null;
    }

    /** Whether the room can be entered by this door: it is usable and not exit-only. */
    public boolean enterable() {
        return usable() && !exitOnly;
    }
}
