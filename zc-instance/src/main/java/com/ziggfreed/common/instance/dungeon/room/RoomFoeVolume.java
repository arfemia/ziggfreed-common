package com.ziggfreed.common.instance.dungeon.room;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.joml.Vector3d;

import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.DoorSpec;
import com.ziggfreed.common.instance.dungeon.layout.Facing;

/**
 * The trigger volume a room's foes ride on ({@link RoomFoes}), written as the engine encodes a {@code TriggerVolume} in
 * the room's own frame, so it is scoped and turned with the room like any volume its prefab holds. Every part of it is
 * a native Type: {@code SpawnNpc} raises each foe on the room's own spawn spots (its prefab's markers), so the
 * creatures come into the world as any spawn does (Mob Scaling and the omens see them as they see every creature);
 * {@code ReplaceBlockType} closes and opens the doorways; {@code EntityCountCondition} watches the foes it raised;
 * {@code ZigRunMilestone} counts the clear.
 *
 * <p>A sealed fight: the first player in seals every doorway's air with the plan's bars and wakes the foes; once none
 * of them stands, the doorways open, the room says {@code Cleared} and the run counts {@code Room_Cleared}. If every
 * player leaves first (fallen, sent to a checkpoint), the doorways open anyway and the clear still counts when the last
 * foe falls. An open pack wakes as the first player comes in, and counts nothing.
 */
final class RoomFoeVolume {

    /** The block a doorway's air is, before and after it is barred. */
    private static final String EMPTY = "Empty";

    /** How far either side of a doorway's centre its bars reach, and how high. */
    private static final int DOOR_HALF_WIDTH = 2;
    private static final int DOOR_HEIGHT = 4;

    /** Seconds from the seal before the clear check starts, so a slow spawn cannot open the room at once. */
    private static final double ARM_SECONDS = 1.5;

    /** Seconds between the clear checks. */
    private static final double CHECK_SECONDS = 0.5;

    private static final String STATE = "State";

    /**
     * One foe on a spot.
     *
     * @param role the role it rises as
     * @param at   where it rises, in the room's own frame
     */
    record Foe(@Nonnull String role, @Nonnull Vector3d at) {
    }

    private RoomFoeVolume() {
    }

    /** The cell the volume's box starts at: its position in the room's own frame. */
    @Nonnull
    static Vector3d corner(@Nonnull Box localBox) {
        Cell min = localBox.min();
        return new Vector3d(min.x(), min.y(), min.z());
    }

    /**
     * The volume, as its {@code TriggerVolume} component encodes, for {@code foes} in a room whose own box is
     * {@code localBox}: sealed through {@code doors} with {@code bars} (null bars none) when {@code sealed}.
     */
    @Nonnull
    static BsonDocument of(@Nonnull String name, @Nonnull Box localBox, @Nonnull List<DoorSpec> doors,
            @Nullable String bars, boolean sealed, @Nonnull List<Foe> foes) {
        Cell min = localBox.min();
        Cell max = localBox.max();
        BsonArray effects = new BsonArray();
        BsonArray conditions = new BsonArray();
        conditions.add(tagIs("ENTER", 1, "Ready"));
        for (Foe foe : foes) {
            BsonDocument spawn = effect("SpawnNpc", "ENTER", 1, 0.0, 0.0);
            spawn.put("NpcType", new BsonString(foe.role()));
            spawn.put("Origin", new BsonString("VolumeOrigin"));
            spawn.put("Offset", vec(foe.at().x - min.x(), foe.at().y - min.y(), foe.at().z - min.z()));
            spawn.put("Count", new BsonInt32(1));
            effects.add(spawn);
        }
        if (!sealed) {
            effects.add(setState("ENTER", 1, 0.0, "Woken"));
            return volume(name, max.x() - min.x() + 1, max.y() - min.y() + 1, max.z() - min.z() + 1, effects,
                    conditions);
        }
        List<Cell[]> doorways = new ArrayList<>(doors.size());
        for (DoorSpec door : doors) {
            Cell[] doorway = doorway(door);
            if (doorway != null) {
                doorways.add(doorway);
            }
        }
        boolean barred = bars != null && !bars.isBlank();
        if (barred) {
            for (Cell[] doorway : doorways) {
                effects.add(swap("ENTER", 1, min, doorway, EMPTY, bars));
            }
        }
        effects.add(setState("ENTER", 1, 0.0, "Arming"));
        effects.add(setState("ENTER", 1, ARM_SECONDS, "Sealed"));

        // The clear: none of the room's foes stands, sealed or opened early.
        BsonArray roles = new BsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (Foe foe : foes) {
            if (seen.add(foe.role())) {
                roles.add(new BsonString(foe.role()));
            }
        }
        for (int entry : new int[] {2, 3}) {
            if (barred && entry == 2) {
                for (Cell[] doorway : doorways) {
                    effects.add(withInterval(swap("TICK", entry, min, doorway, bars, EMPTY)));
                }
            }
            effects.add(withInterval(setState("TICK", entry, 0.0, "Cleared")));
            BsonDocument milestone = withInterval(effect("ZigRunMilestone", "TICK", entry, 0.0, 0.0));
            milestone.put("Id", new BsonString("Room_Cleared"));
            effects.add(milestone);
            conditions.add(tagIs("TICK", entry, entry == 2 ? "Sealed" : "Opened"));
            BsonDocument none = condition("EntityCountCondition", "TICK", entry);
            none.put("Comparison", new BsonString("Exactly"));
            none.put("Count", new BsonInt32(0));
            none.put("EntityType", roles.clone());
            conditions.add(none);
        }

        // Everyone left the room while its foes stood: the doorways open, so the party can come back in.
        if (barred) {
            for (Cell[] doorway : doorways) {
                effects.add(swap("EXIT", 4, min, doorway, bars, EMPTY));
            }
        }
        effects.add(setState("EXIT", 4, 0.0, "Opened"));
        conditions.add(tagIs("EXIT", 4, "Sealed"));
        BsonDocument nobody = condition("PlayerCountCondition", "EXIT", 4);
        nobody.put("Comparison", new BsonString("Exactly"));
        nobody.put("Count", new BsonInt32(0));
        conditions.add(nobody);
        return volume(name, max.x() - min.x() + 1, max.y() - min.y() + 1, max.z() - min.z() + 1, effects, conditions);
    }

    /**
     * A doorway's cells, lowest corner then highest: one step out of the room from the door's floor cell, as wide as
     * a doorway may be either side of its centre and as high as a player, so the bars fill the opening's air and touch
     * no wall. Null for a door with no facing.
     */
    @Nullable
    static Cell[] doorway(@Nonnull DoorSpec door) {
        Facing facing = door.facing();
        if (facing == null) {
            return null;
        }
        Cell at = door.at();
        int x = at.x() + facing.dx();
        int z = at.z() + facing.dz();
        int acrossX = facing.dx() == 0 ? DOOR_HALF_WIDTH : 0;
        int acrossZ = facing.dz() == 0 ? DOOR_HALF_WIDTH : 0;
        return new Cell[] {new Cell(x - acrossX, at.y() + 1, z - acrossZ),
                new Cell(x + acrossX, at.y() + DOOR_HEIGHT, z + acrossZ)};
    }

    @Nonnull
    private static BsonDocument volume(@Nonnull String name, int sizeX, int sizeY, int sizeZ, @Nonnull BsonArray effects,
            @Nonnull BsonArray conditions) {
        BsonDocument shape = new BsonDocument();
        shape.put("Type", new BsonString("Box"));
        shape.put("Min", vec(0, 0, 0));
        shape.put("Max", vec(sizeX, sizeY, sizeZ));
        BsonDocument tags = new BsonDocument();
        tags.put(STATE, new BsonString("Ready"));
        BsonArray targets = new BsonArray();
        targets.add(new BsonString("Player"));
        BsonDocument out = new BsonDocument();
        out.put("Shape", shape);
        out.put("Effects", effects);
        out.put("Conditions", conditions);
        out.put("Enabled", BsonBoolean.TRUE);
        out.put("TargetTypes", targets);
        out.put("KeepLoaded", BsonBoolean.FALSE);
        out.put("Name", new BsonString(name));
        out.put("Tags", tags);
        return out;
    }

    /** Replace {@code from} with {@code to} over the cells of {@code doorway}, offset from the volume's corner. */
    @Nonnull
    private static BsonDocument swap(@Nonnull String event, int entry, @Nonnull Cell corner, @Nonnull Cell[] doorway,
            @Nonnull String from, @Nonnull String to) {
        Cell lo = doorway[0];
        Cell hi = doorway[1];
        BsonDocument e = effect("ReplaceBlockType", event, entry, 0.0, 0.0);
        BsonArray fromTypes = new BsonArray();
        fromTypes.add(new BsonString(from));
        e.put("FromBlockTypes", fromTypes);
        e.put("FromBlockState", new BsonString(""));
        e.put("ToBlockType", new BsonString(to));
        e.put("ToBlockState", new BsonString(""));
        e.put("Bounds", new BsonString("Aabb"));
        // An Aabb spans floor(origin + Offset) to floor(origin + Offset + size): half a block short of the next cell.
        e.put("X", new BsonDouble(hi.x() - lo.x() + 0.5));
        e.put("Y", new BsonDouble(hi.y() - lo.y() + 0.5));
        e.put("Z", new BsonDouble(hi.z() - lo.z() + 0.5));
        e.put("Offset", vec(lo.x() - corner.x(), lo.y() - corner.y(), lo.z() - corner.z()));
        e.put("Origin", new BsonString("VolumeOrigin"));
        e.put("Rotation", new BsonString("None"));
        e.put("Pitch", new BsonString("None"));
        e.put("Roll", new BsonString("None"));
        return e;
    }

    @Nonnull
    private static BsonDocument setState(@Nonnull String event, int entry, double delay, @Nonnull String value) {
        BsonDocument e = effect("ModifyTags", event, entry, delay, 0.0);
        e.put("Operation", new BsonString("Set"));
        e.put("TagKey", new BsonString(STATE));
        e.put("TagValue", new BsonString(value));
        e.put("MatchKey", new BsonString(""));
        e.put("MatchValue", new BsonString(""));
        e.put("Radius", new BsonDouble(50.0));
        e.put("Center", new BsonString("Volume"));
        return e;
    }

    @Nonnull
    private static BsonDocument tagIs(@Nonnull String event, int entry, @Nonnull String value) {
        BsonDocument c = condition("TagCondition", event, entry);
        c.put("Source", new BsonString("Self"));
        c.put("TagKey", new BsonString(STATE));
        c.put("Comparison", new BsonString("Exactly"));
        c.put("CompareAgainst", new BsonString("Value"));
        c.put("TagValue", new BsonString(value));
        c.put("Inverted", BsonBoolean.FALSE);
        c.put("MatchKey", new BsonString(""));
        c.put("MatchValue", new BsonString(""));
        c.put("Radius", new BsonDouble(50.0));
        c.put("Center", new BsonString("Volume"));
        return c;
    }

    @Nonnull
    private static BsonDocument effect(@Nonnull String type, @Nonnull String event, int entry, double delay,
            double interval) {
        BsonDocument e = new BsonDocument();
        e.put("Type", new BsonString(type));
        e.put("Event", new BsonString(event));
        e.put("Interval", new BsonDouble(interval));
        e.put("Delay", new BsonDouble(delay));
        if (entry != 0) {
            e.put("Entry", new BsonInt32(entry));
        }
        return e;
    }

    @Nonnull
    private static BsonDocument withInterval(@Nonnull BsonDocument effect) {
        effect.put("Interval", new BsonDouble(CHECK_SECONDS));
        return effect;
    }

    @Nonnull
    private static BsonDocument condition(@Nonnull String type, @Nonnull String event, int entry) {
        BsonDocument c = new BsonDocument();
        c.put("Type", new BsonString(type));
        c.put("Event", new BsonString(event));
        if (entry != 0) {
            c.put("Entry", new BsonInt32(entry));
        }
        return c;
    }

    @Nonnull
    private static BsonDocument vec(double x, double y, double z) {
        BsonDocument v = new BsonDocument();
        v.put("X", new BsonDouble(x));
        v.put("Y", new BsonDouble(y));
        v.put("Z", new BsonDouble(z));
        return v;
    }
}
