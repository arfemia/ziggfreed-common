package com.ziggfreed.common.instance.dungeon.layout.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.DoorSpec;
import com.ziggfreed.common.instance.dungeon.layout.Facing;
import com.ziggfreed.common.instance.dungeon.layout.RoomSpec;

/**
 * A dungeon room file's codec: every leaf and group reads as written (folded where the generator folds it), is
 * written back and reads the same, an empty file reads every documented default, and under {@code Parent} a child
 * keeps every leaf it does not restate. Untagged: nothing here builds an engine item.
 */
class DungeonRoomAssetCodecTest {

    private static final String STONE_ROOM = """
            {
              "Prefab": " Dungeon.Stone.Level_1.Room_Small.* ",
              "Box": { "Min": { "X": -8, "Z": -8 }, "Max": { "X": 8, "Y": 9, "Z": 8 } },
              "Doors": [ { "At": { "Z": -8 }, "Facing": "north", "Kind": "Stone_Small" },
                         { "At": { "X": 8 }, "Facing": "East" },
                         { "At": { "Z": 8 }, "Facing": "Up", "Kind": "Stone_Small" } ],
              "Roles": ["Combat", " puzzle ", "COMBAT"],
              "Tags": { "Kit": ["Zc_Kit_Stone"], "Stratum": ["Ruins"] },
              "Weight": 2.5,
              "Depth": { "Min": 0.25, "Max": 0.75 },
              "Needs": ["Clue"],
              "Keep": { "Markers": true, "Containers": false },
              "Fill": " Rock_Stone "
            }
            """;

    @Test
    void everyLeafRoundTripsThroughTheCodec() throws IOException {
        DungeonRoomAsset read = decode("Stone_Room", STONE_ROOM, null);
        assertStoneRoom(read.toSpec());

        DungeonRoomAsset again = decode("Stone_Room",
                DungeonRoomAsset.CODEC.encode(read, new ExtraInfo()).toJson(), null);
        assertEquals(read.toSpec(), again.toSpec(), "written back, the file reads the same");
        ObjectSchema schema = (ObjectSchema) DungeonRoomAsset.CODEC.toSchema(new SchemaContext());
        assertNotNull(schema.getProperties().get("Doors"), "the schema the Asset Editor loads declares the doors");
        assertNotNull(schema.getProperties().get("Fill"), "and the fill block");
    }

    @Test
    void anEmptyFileReadsEveryDefault() throws IOException {
        RoomSpec bare = decode("Bare", "{}", null).toSpec();
        assertEquals("bare", bare.id());
        assertEquals("", bare.prefab(), "no Prefab: nothing to paste, so the room is never placed");
        assertEquals(new Box(Cell.ZERO, Cell.ZERO), bare.box());
        assertTrue(bare.doors().isEmpty());
        assertTrue(bare.roles().isEmpty());
        assertTrue(bare.tags().isEmpty());
        assertEquals(1.0, bare.weight(), "Weight defaults to 1");
        assertEquals(0.0, bare.depthMin());
        assertEquals(1.0, bare.depthMax(), "Depth defaults to the whole path");
        assertTrue(bare.needs().isEmpty());
        assertFalse(bare.keepMarkers(), "Keep defaults to neither");
        assertFalse(bare.keepContainers());
        assertNull(bare.fill(), "no Fill: the stratum's, else none");
    }

    @Test
    void aChildKeepsWhatItDoesNotRestate() throws IOException {
        DungeonRoomAsset parent = decode("Stone_Room", STONE_ROOM, null);
        RoomSpec child = decode("Stone_Room_Heavy", "{ \"Weight\": 5, \"Fill\": \"Rock_Basalt\" }", parent).toSpec();
        RoomSpec base = parent.toSpec();
        assertEquals("stone_room_heavy", child.id());
        assertEquals(5.0, child.weight());
        assertEquals("Rock_Basalt", child.fill());
        assertEquals(base.prefab(), child.prefab());
        assertEquals(base.box(), child.box());
        assertEquals(base.doors(), child.doors());
        assertEquals(base.roles(), child.roles());
        assertEquals(base.tags(), child.tags(), "the engine's Tags are inherited too");
        assertEquals(base.needs(), child.needs());
        assertEquals(base.depthMin(), child.depthMin());
        assertEquals(base.depthMax(), child.depthMax());
        assertTrue(child.keepMarkers());
    }

    private static void assertStoneRoom(@Nonnull RoomSpec spec) {
        assertEquals("stone_room", spec.id(), "the id is folded");
        assertEquals("Dungeon.Stone.Level_1.Room_Small.*", spec.prefab(), "the prefab is trimmed, never folded");
        assertTrue(spec.isPool());
        assertEquals(new Box(new Cell(-8, 0, -8), new Cell(8, 9, 8)), spec.box(), "an unauthored axis is 0");
        assertEquals(List.of(
                        new DoorSpec(new Cell(0, 0, -8), Facing.NORTH, "stone_small"),
                        new DoorSpec(new Cell(8, 0, 0), Facing.EAST, DoorSpec.DEFAULT_KIND),
                        new DoorSpec(new Cell(0, 0, 8), null, "stone_small")),
                spec.doors(), "a facing reads without regard to case, an unknown one as none; a kind folds");
        assertEquals(List.of("combat", "puzzle"), spec.roles(), "roles fold, trimmed, a repeat dropped");
        assertEquals(6, spec.tags().size(), () -> "each key, value and Key=Value: " + spec.tags());
        assertTrue(spec.carries("Zc_Kit_Stone") && spec.carries("Kit") && spec.carries("Kit=Zc_Kit_Stone"));
        assertTrue(spec.carries("Ruins") && spec.carries("Stratum") && spec.carries("Stratum=Ruins"));
        assertEquals(2.5, spec.weight());
        assertEquals(0.25, spec.depthMin());
        assertEquals(0.75, spec.depthMax());
        assertEquals(List.of("clue"), spec.needs());
        assertTrue(spec.keepMarkers());
        assertFalse(spec.keepContainers());
        assertEquals("Rock_Stone", spec.fill(), "the fill block is trimmed, never folded");
    }

    @Nonnull
    private static DungeonRoomAsset decode(@Nonnull String id, @Nonnull String json,
            @Nullable DungeonRoomAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(DungeonRoomAsset.class, id,
                parent == null ? null : parent.getId());
        return DungeonRoomAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }
}
