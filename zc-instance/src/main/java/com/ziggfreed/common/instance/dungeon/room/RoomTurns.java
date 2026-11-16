package com.ziggfreed.common.instance.dungeon.room;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonString;
import org.bson.BsonValue;

import com.ziggfreed.common.instance.dungeon.layout.QuarterTurns;

/**
 * How a room's logic turns with its room, pure: the blocks of a turned room land by cell index, the engine's
 * {@code PrefabRotation} ({@code (x, z)} to {@code (z, -x)} per quarter turn, about the anchor CELL), so its logic is
 * turned the same way, which is a turn about the anchor cell's centre rather than its corner.
 *
 * <ul>
 *   <li>A point of the room's own frame (a volume's origin, a marker, a point) lands at {@link #placedX},
 *       {@link #placedZ}: the plain turn plus the shift that moves the pivot from the anchor's corner to its centre.
 *       A volume's origin authored on its cell's corner stays on a corner of the turned cell, and its Box turned
 *       plainly about that origin ({@link #turnShape}) covers exactly the cells its blocks were turned onto.</li>
 *   <li>Everything a volume holds relative to its origin turns plainly about it: the engine's own
 *       {@code rotateInPlace} on each effect, condition and rule does that (block targets about their cell's
 *       centre), so the placer leaves those to it, once, and turns here, in the JSON, only the two the engine turns
 *       otherwise ({@link #turnEffects}): a {@code Teleport}, whose facing the engine adds to its pitch slot
 *       ({@code Rotation.Y}) though it reads the facing from {@code Rotation.X}, and a {@code PastePrefab}, whose
 *       anchor cell the engine turns about the volume's corner, a block off from the room's blocks.</li>
 * </ul>
 *
 * <p>A turn's yaw is {@code +pi/2} per quarter ({@link #yawRadians}); never the engine's
 * {@code PrefabRotation.getYaw()}, which reads {@code -pi/2} for one quarter turn.
 */
public final class RoomTurns {

    /** The engine's block rotation keys, in quarter-turn order. */
    private static final String[] BLOCK_TURNS = {"None", "Ninety", "OneEighty", "TwoSeventy"};

    /** The arrays of a volume or group whose elements carry an effect's {@code Type}. */
    private static final String[] EFFECT_ARRAYS = {"Effects", "RejectionEffects"};

    private RoomTurns() {
    }

    /** The yaw of {@code turns} quarter turns, in radians: {@code +pi/2} each. */
    public static float yawRadians(int turns) {
        return (float) (QuarterTurns.normalize(turns) * (Math.PI / 2.0));
    }

    /** The X of the vector {@code (x, z)} after {@code turns} plain quarter turns ({@link QuarterTurns}). */
    public static double x(double x, double z, int turns) {
        return switch (QuarterTurns.normalize(turns)) {
            case 1 -> z;
            case 2 -> -x;
            case 3 -> -z;
            default -> x;
        };
    }

    /** The Z of the vector {@code (x, z)} after {@code turns} plain quarter turns. */
    public static double z(double x, double z, int turns) {
        return switch (QuarterTurns.normalize(turns)) {
            case 1 -> -x;
            case 2 -> -z;
            case 3 -> x;
            default -> z;
        };
    }

    /**
     * Where the point {@code (x, z)} of a room's own frame (anchor-relative) lands once the room turns
     * {@code turns} times: turned about the anchor cell's centre, as the room's blocks are.
     */
    public static double placedX(double x, double z, int turns) {
        int t = QuarterTurns.normalize(turns);
        return x(x, z, t) + (t == 2 || t == 3 ? 1 : 0);
    }

    /** The Z of {@link #placedX}. */
    public static double placedZ(double x, double z, int turns) {
        int t = QuarterTurns.normalize(turns);
        return z(x, z, t) + (t == 1 || t == 2 ? 1 : 0);
    }

    /** {@code degrees} plus {@code turns} quarter turns, in {@code [0, 360)}. */
    public static double addDegrees(double degrees, int turns) {
        double sum = (degrees + 90.0 * QuarterTurns.normalize(turns)) % 360.0;
        return sum < 0 ? sum + 360.0 : sum;
    }

    // ==================== the JSON a volume carries ====================

    /**
     * Turn a volume's encoded {@code TriggerVolume} in place: its shape always, and, unless it says
     * {@code RotateEffectsOnPaste: false}, the effects the engine would turn wrongly ({@link #turnEffects}).
     */
    public static void turnVolume(@Nonnull BsonDocument volume, int turns) {
        int t = QuarterTurns.normalize(turns);
        if (t == 0) {
            return;
        }
        BsonValue shape = volume.get("Shape");
        if (shape != null && shape.isDocument()) {
            turnShape(shape.asDocument(), t);
        }
        if (rotatesEffects(volume)) {
            turnEffects(volume, t);
        }
    }

    /** Whether the holder's effects turn with it: true unless it says {@code RotateEffectsOnPaste: false}. */
    public static boolean rotatesEffects(@Nonnull BsonDocument holder) {
        BsonValue value = holder.get("RotateEffectsOnPaste");
        return value == null || !value.isBoolean() || value.asBoolean().getValue();
    }

    /**
     * Turn a shape in place about its volume's origin: a {@code Box}'s {@code Min} and {@code Max} turned and put back
     * in order, so it stays axis-aligned and covers whole cells (a box with a rotation of its own keeps its extents,
     * its centre turned and the yaw added to it, as the engine turns one); a {@code Sphere}'s or {@code Cylinder}'s
     * {@code Center} turned.
     */
    public static void turnShape(@Nonnull BsonDocument shape, int turns) {
        int t = QuarterTurns.normalize(turns);
        if (t == 0) {
            return;
        }
        BsonDocument min = doc(shape, "Min");
        BsonDocument max = doc(shape, "Max");
        if (min != null && max != null) {
            BsonDocument rotation = doc(shape, "Rotation");
            if (rotation != null && hasRotation(rotation)) {
                double cx = (num(min, "X") + num(max, "X")) / 2.0;
                double cz = (num(min, "Z") + num(max, "Z")) / 2.0;
                double hx = Math.abs(num(max, "X") - num(min, "X")) / 2.0;
                double hz = Math.abs(num(max, "Z") - num(min, "Z")) / 2.0;
                double nx = x(cx, cz, t);
                double nz = z(cx, cz, t);
                min.put("X", new BsonDouble(nx - hx));
                min.put("Z", new BsonDouble(nz - hz));
                max.put("X", new BsonDouble(nx + hx));
                max.put("Z", new BsonDouble(nz + hz));
                rotation.put("Y", new BsonDouble(num(rotation, "Y") + yawRadians(t)));
                return;
            }
            double ax = x(num(min, "X"), num(min, "Z"), t);
            double az = z(num(min, "X"), num(min, "Z"), t);
            double bx = x(num(max, "X"), num(max, "Z"), t);
            double bz = z(num(max, "X"), num(max, "Z"), t);
            min.put("X", new BsonDouble(Math.min(ax, bx)));
            min.put("Z", new BsonDouble(Math.min(az, bz)));
            max.put("X", new BsonDouble(Math.max(ax, bx)));
            max.put("Z", new BsonDouble(Math.max(az, bz)));
            return;
        }
        turnVector(doc(shape, "Center"), t);
    }

    /**
     * Turn, in place, the effects of a volume or group the placer does not hand to the engine's own
     * {@code rotateInPlace}, so each is turned exactly once:
     * <ul>
     *   <li>{@code Teleport} anchored on the volume ({@code RelativeToVolume}, not {@code RelativeToEntity}): its
     *       {@code Position} turned plainly, and with {@code UseRotation} its facing, {@code Rotation.X}, given the
     *       turn's yaw; its pitch, {@code Rotation.Y}, is left alone;</li>
     *   <li>{@code PastePrefab} anchored on the volume ({@code Origin} {@code VolumeOrigin}, its default): its anchor
     *       cell turned about the cell's centre (an unwritten {@code Position} is the volume's own cell), and its
     *       block {@code Rotation} given the turns.</li>
     * </ul>
     */
    public static void turnEffects(@Nonnull BsonDocument holder, int turns) {
        int t = QuarterTurns.normalize(turns);
        if (t == 0) {
            return;
        }
        for (String key : EFFECT_ARRAYS) {
            BsonValue list = holder.get(key);
            if (list == null || !list.isArray()) {
                continue;
            }
            for (BsonValue element : list.asArray()) {
                if (!element.isDocument()) {
                    continue;
                }
                BsonDocument effect = element.asDocument();
                String type = str(effect, "Type");
                if ("Teleport".equals(type)) {
                    turnTeleport(effect, t);
                } else if ("PastePrefab".equals(type)) {
                    turnPastePrefab(effect, t);
                }
            }
        }
    }

    private static void turnTeleport(@Nonnull BsonDocument effect, int t) {
        if (!bool(effect, "RelativeToVolume") || bool(effect, "RelativeToEntity")) {
            return;
        }
        turnVector(doc(effect, "Position"), t);
        BsonDocument rotation = doc(effect, "Rotation");
        if (bool(effect, "UseRotation") && rotation != null) {
            rotation.put("X", new BsonDouble(addDegrees(num(rotation, "X"), t)));
        }
    }

    private static void turnPastePrefab(@Nonnull BsonDocument effect, int t) {
        String origin = str(effect, "Origin");
        if (origin != null && !"VolumeOrigin".equals(origin)) {
            return;
        }
        BsonDocument position = doc(effect, "Position");
        if (position == null) {
            position = new BsonDocument("X", new BsonDouble(0)).append("Y", new BsonDouble(0))
                    .append("Z", new BsonDouble(0));
            effect.put("Position", position);
        }
        double cx = Math.floor(num(position, "X")) + 0.5;
        double cz = Math.floor(num(position, "Z")) + 0.5;
        position.put("X", new BsonDouble(x(cx, cz, t)));
        position.put("Z", new BsonDouble(z(cx, cz, t)));
        String rotation = str(effect, "Rotation");
        int from = 0;
        for (int i = 0; i < BLOCK_TURNS.length; i++) {
            if (BLOCK_TURNS[i].equals(rotation)) {
                from = i;
            }
        }
        effect.put("Rotation", new BsonString(BLOCK_TURNS[(from + t) % BLOCK_TURNS.length]));
    }

    // ==================== reading the JSON ====================

    private static void turnVector(@Nullable BsonDocument vector, int t) {
        if (vector == null) {
            return;
        }
        double vx = num(vector, "X");
        double vz = num(vector, "Z");
        vector.put("X", new BsonDouble(x(vx, vz, t)));
        vector.put("Z", new BsonDouble(z(vx, vz, t)));
    }

    private static boolean hasRotation(@Nonnull BsonDocument rotation) {
        return Math.abs(num(rotation, "X")) > 1e-6 || Math.abs(num(rotation, "Y")) > 1e-6
                || Math.abs(num(rotation, "Z")) > 1e-6;
    }

    @Nullable
    static BsonDocument doc(@Nonnull BsonDocument parent, @Nonnull String key) {
        BsonValue value = parent.get(key);
        return value != null && value.isDocument() ? value.asDocument() : null;
    }

    @Nullable
    static BsonArray array(@Nonnull BsonDocument parent, @Nonnull String key) {
        BsonValue value = parent.get(key);
        return value != null && value.isArray() ? value.asArray() : null;
    }

    static double num(@Nonnull BsonDocument parent, @Nonnull String key) {
        BsonValue value = parent.get(key);
        return value != null && value.isNumber() ? value.asNumber().doubleValue() : 0.0;
    }

    @Nullable
    static String str(@Nonnull BsonDocument parent, @Nonnull String key) {
        BsonValue value = parent.get(key);
        return value != null && value.isString() ? value.asString().getValue() : null;
    }

    static boolean bool(@Nonnull BsonDocument parent, @Nonnull String key) {
        BsonValue value = parent.get(key);
        return value != null && value.isBoolean() && value.asBoolean().getValue();
    }
}
