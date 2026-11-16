package com.ziggfreed.common.instance.dungeon.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.builtin.triggervolumes.YawRotation;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;

/**
 * A turned room's volumes land on the cells its turned blocks do (PD3, from PD7a's verifier: the engine's own box turn
 * pivots on the anchor's corner and lands a block off at turns 1 to 3). Every template anchors a volume on its corner
 * cell with a Box from 0 to its size; the placer puts its origin at {@link RoomTurns#placedX}, {@link RoomTurns#placedZ}
 * and turns its Box ({@link RoomTurns#turnShape}), and the cells whose centre the box then holds (the engine's
 * containment) must be exactly the engine's own block turn ({@code PrefabRotation}) of the cells it covered. A block
 * target (a PastePrefab's cell, an engine-turned block offset) must land on its turned cell wherever in its own cell the
 * volume's origin stands (PD3's verifier). Untagged: nothing here builds an engine item.
 */
class RoomTurnsTest {

    @Test
    void aCornerAnchoredCellLandsOnTheBlockItSitsOnAtEveryTurn() {
        for (int turns = 0; turns < 4; turns++) {
            assertEquals(blockCells(3, 1, -2, 1, 1, 1, turns), volumeCells(3, 1, -2, 1, 1, 1, turns),
                    "a 1x1x1 volume at turn " + turns);
            assertEquals(blockCells(0, 0, 0, 1, 1, 1, turns), volumeCells(0, 0, 0, 1, 1, 1, turns),
                    "a 1x1x1 volume on the anchor itself at turn " + turns);
        }
    }

    @Test
    void anNByMVolumeCoversItsTurnedBlocksAtEveryTurn() {
        for (int turns = 0; turns < 4; turns++) {
            assertEquals(blockCells(-5, 0, 7, 4, 2, 3, turns), volumeCells(-5, 0, 7, 4, 2, 3, turns),
                    "a 4 by 3 volume at turn " + turns);
            assertEquals(blockCells(2, -1, -6, 1, 3, 5, turns), volumeCells(2, -1, -6, 1, 3, 5, turns),
                    "a 1 by 5 volume at turn " + turns);
        }
    }

    @Test
    void aPasteOffAVolumeOriginAnywhereInItsCellLandsOnTheTurnedCell() {
        // The cell a PastePrefab pastes at is floor(origin + Position); with the volume's origin off its cell's corner
        // (a centre, a quarter in) it must still land where the room's own blocks turn that cell. The engine's
        // block-offset turn (YawRotation.rotateBlockOffset), handed the origin before the turn as the placer hands it,
        // must land there too.
        double[][] origins = {{0.5, 0.5}, {2.5, -1.5}, {3.25, 0.75}, {-4.0, 2.5}};
        double[][] offsets = {{0, 0}, {2, -3}, {-1.5, 0.25}};
        String[] blockTurns = {"None", "Ninety", "OneEighty", "TwoSeventy"};
        for (int turns = 1; turns < 4; turns++) {
            PrefabRotation rotation = PrefabRotation.VALUES[turns];
            for (double[] origin : origins) {
                double ox = RoomTurns.placedX(origin[0], origin[1], turns);
                double oz = RoomTurns.placedZ(origin[0], origin[1], turns);
                for (double[] offset : offsets) {
                    int ax = (int) Math.floor(origin[0] + offset[0]);
                    int az = (int) Math.floor(origin[1] + offset[1]);
                    List<Integer> want = List.of(rotation.getX(ax, az), rotation.getZ(ax, az));
                    String where = "origin " + origin[0] + " " + origin[1] + ", offset " + offset[0] + " " + offset[1]
                            + ", turn " + turns;

                    BsonDocument volume = BsonDocument.parse("{\"Effects\": [{\"Type\": \"PastePrefab\", \"Origin\":"
                            + " \"VolumeOrigin\", \"Rotation\": \"Ninety\", \"Position\": {\"X\": " + offset[0]
                            + ", \"Y\": 0.0, \"Z\": " + offset[1] + "}}]}");
                    RoomTurns.turnEffects(volume, origin[0], origin[1], turns);
                    BsonDocument paste = volume.getArray("Effects").get(0).asDocument();
                    BsonDocument position = paste.getDocument("Position");
                    assertEquals(want, List.of((int) Math.floor(ox + position.getNumber("X").doubleValue()),
                            (int) Math.floor(oz + position.getNumber("Z").doubleValue())), "a PastePrefab, " + where);
                    assertEquals(blockTurns[(1 + turns) % 4], paste.getString("Rotation").getValue(),
                            "its block rotation, " + where);

                    Vector3d engine = new Vector3d(offset[0], 0.0, offset[1]);
                    YawRotation.rotateBlockOffset(engine, RoomTurns.yawRadians(turns),
                            new Vector3d(origin[0], 0.0, origin[1]));
                    assertEquals(want, List.of((int) Math.floor(ox + engine.x), (int) Math.floor(oz + engine.z)),
                            "a block offset the engine turns, " + where);
                }
            }
            BsonDocument bare = BsonDocument.parse("{\"Effects\": [{\"Type\": \"PastePrefab\"}]}");
            RoomTurns.turnEffects(bare, 0.5, 0.5, turns);
            BsonDocument position = bare.getArray("Effects").get(0).asDocument().getDocument("Position");
            double ox = RoomTurns.placedX(0.5, 0.5, turns);
            double oz = RoomTurns.placedZ(0.5, 0.5, turns);
            assertEquals(List.of(rotation.getX(0, 0), rotation.getZ(0, 0)),
                    List.of((int) Math.floor(ox + position.getNumber("X").doubleValue()),
                            (int) Math.floor(oz + position.getNumber("Z").doubleValue())),
                    "an unwritten Position pastes on the volume's own cell, turn " + turns);
        }
    }

    @Test
    void aTurnsYawTurnsAFacingTheWayTheBlocksTurn() {
        // Yaw 0 looks toward -Z: the look direction is (-sin yaw, -cos yaw). Its turned yaw must look the way the
        // plain turn of the direction points, so the yaw is +pi/2 a quarter (the engine's getYaw() reads -pi/2).
        for (int turns = 0; turns < 4; turns++) {
            for (double degrees : new double[] {0, 37.5, 90, 200}) {
                double yaw = Math.toRadians(degrees);
                double dx = -Math.sin(yaw);
                double dz = -Math.cos(yaw);
                double turned = yaw + RoomTurns.yawRadians(turns);
                assertEquals(RoomTurns.x(dx, dz, turns), -Math.sin(turned), 1e-6, "facing x, turn " + turns);
                assertEquals(RoomTurns.z(dx, dz, turns), -Math.cos(turned), 1e-6, "facing z, turn " + turns);
                double facing = Math.toRadians(RoomTurns.addDegrees(degrees, turns));
                assertEquals(RoomTurns.x(dx, dz, turns), -Math.sin(facing), 1e-6, "a Rotation.X facing, turn " + turns);
            }
        }
    }

    /** The engine's own turn of every block cell the volume covered, its corner at (cx, cy, cz). */
    @Nonnull
    private static Set<List<Integer>> blockCells(int cx, int cy, int cz, int sx, int sy, int sz, int turns) {
        PrefabRotation rotation = PrefabRotation.VALUES[turns];
        Set<List<Integer>> cells = new HashSet<>();
        for (int x = cx; x < cx + sx; x++) {
            for (int y = cy; y < cy + sy; y++) {
                for (int z = cz; z < cz + sz; z++) {
                    cells.add(List.of(rotation.getX(x, z), y, rotation.getZ(x, z)));
                }
            }
        }
        return cells;
    }

    /** The cells whose centre the placed volume's box holds, the volume authored at (cx, cy, cz) with Box 0..size. */
    @Nonnull
    private static Set<List<Integer>> volumeCells(int cx, int cy, int cz, int sx, int sy, int sz, int turns) {
        BsonDocument shape = BsonDocument.parse("{\"Type\": \"Box\", \"Min\": {\"X\": 0.0, \"Y\": 0.0, \"Z\": 0.0},"
                + " \"Max\": {\"X\": " + sx + ".0, \"Y\": " + sy + ".0, \"Z\": " + sz + ".0}}");
        RoomTurns.turnShape(shape, turns);
        double ox = RoomTurns.placedX(cx, cz, turns);
        double oy = cy;
        double oz = RoomTurns.placedZ(cx, cz, turns);
        BsonDocument min = shape.getDocument("Min");
        BsonDocument max = shape.getDocument("Max");
        double minX = ox + min.getNumber("X").doubleValue();
        double minY = oy + min.getNumber("Y").doubleValue();
        double minZ = oz + min.getNumber("Z").doubleValue();
        double maxX = ox + max.getNumber("X").doubleValue();
        double maxY = oy + max.getNumber("Y").doubleValue();
        double maxZ = oz + max.getNumber("Z").doubleValue();
        Set<List<Integer>> cells = new HashSet<>();
        for (int x = (int) Math.floor(minX) - 1; x <= (int) Math.ceil(maxX); x++) {
            for (int y = (int) Math.floor(minY) - 1; y <= (int) Math.ceil(maxY); y++) {
                for (int z = (int) Math.floor(minZ) - 1; z <= (int) Math.ceil(maxZ); z++) {
                    if (x + 0.5 >= minX && x + 0.5 <= maxX && y + 0.5 >= minY && y + 0.5 <= maxY
                            && z + 0.5 >= minZ && z + 0.5 <= maxZ) {
                        cells.add(List.of(x, y, z));
                    }
                }
            }
        }
        return cells;
    }
}
