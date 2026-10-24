package com.ziggfreed.common.instance.dungeon.layout;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One room of a {@link Layout}, where it stands and how it joins the rest.
 *
 * @param index     its index in the layout; the path's rooms come first, the entry at 0
 * @param roomId    the room file it is, folded
 * @param variant   which prefab of the room's pool it pastes, an index into the pool's fixed order (0 for one prefab)
 * @param origin    where its prefab anchor stands, in the layout's frame
 * @param rotation  its quarter turns, 0 to 3 ({@link QuarterTurns}; the engine's {@code PrefabRotation.VALUES} index)
 * @param box       the cells it covers, in the layout's frame
 * @param fill      the block the painter fills the cells its prefab leaves unlisted inside {@code box} with: the
 *                  room's own {@code Fill}, else its stratum's, else null for none
 * @param role      the role it fills here, folded
 * @param part      what it is to the layout
 * @param beat      the index of the plan beat it fills (a link: the beat it leads into); -1 off the path
 * @param stratum   the index of the stratum it belongs to, or -1 for a plan without strata
 * @param pathIndex its place along the path; off the path, the place of the path room its branch leaves from
 * @param depth     its {@code pathIndex} as a fraction of the path, 0 at the entry and 1 at the last room
 * @param parent    the room it was joined onto, or -1 for the entry
 * @param branch    the branch it stands on, or -1 on the path (a cap takes its room's)
 * @param step      how far along its branch it stands, 1 for the room next to the path; 0 on the path
 * @param answers   the index of the room whose need it fills, or -1
 * @param doors     each of its doors in the room file's order: where it opened and what it joined
 */
public record PlacedRoom(int index, @Nonnull String roomId, int variant, @Nonnull Cell origin, int rotation,
                         @Nonnull Box box, @Nullable String fill, @Nonnull String role, @Nonnull Part part, int beat,
                         int stratum,
                         int pathIndex, double depth, int parent, int branch, int step, int answers,
                         @Nonnull List<DoorLink> doors) {

    public PlacedRoom {
        doors = List.copyOf(doors);
    }

    /** What a room is to the layout. */
    public enum Part {
        /** A room filling a beat of the path. */
        PATH,
        /** A connector between two beats, on the path. */
        LINK,
        /** The room a side branch starts with. */
        GATE,
        /** A side branch's room. */
        BRANCH,
        /** A room on a branch of its own that fills a later room's need. */
        NEED,
        /** A room closing a door left open. */
        CAP
    }

    /** Whether it stands on the path itself. */
    public boolean onPath() {
        return part == Part.PATH || part == Part.LINK;
    }

    /**
     * One door of a placed room, in the layout's frame.
     *
     * @param door     its index in the room file's door list
     * @param at       the floor cell at the doorway's centre, just inside the room
     * @param facing   the way it opens, turned with the room; null for a door its file gives no known facing
     * @param kind     its socket kind, folded
     * @param room     the room it joins (a cap room when it was capped), or -1 for a door with no facing
     * @param roomDoor the door of that room it meets, or -1
     * @param capped   whether it was left open and closed by a cap room
     */
    public record DoorLink(int door, @Nonnull Cell at, @Nullable Facing facing, @Nonnull String kind, int room,
                           int roomDoor, boolean capped) {
    }
}
