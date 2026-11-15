package com.ziggfreed.common.instance.dungeon.layout.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Beat;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Branch;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Count;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Links;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Stratum;

/**
 * A dungeon layout plan file's codec: every leaf and group reads as written (folded where the generator folds it),
 * is written back and reads the same, an empty file reads every documented default, and under {@code Parent} a
 * child keeps every leaf it does not restate. Untagged: nothing here builds an engine item.
 */
class DungeonLayoutAssetCodecTest {

    private static final String STONE_RUN = """
            {
              "Rooms": { "Tags": ["Zc_Kit_Stone"] },
              "Strata": [ { "AnyOf": ["Ruins", " crypt "], "Fill": " Rock_Stone " }, { "AnyOf": ["Void"] } ],
              "Path": [ { "Role": "Entry" }, { "Role": "Combat", "Count": { "Min": 2, "Max": 3 } },
                        { "Role": "Puzzle", "Count": { "Min": 2 } }, { "Role": "Boss", "Stratum": 1 } ],
              "Links": { "Role": "Corridor", "Count": { "Min": 0, "Max": 2 } },
              "Branches": [ { "Role": "Treasure", "Count": { "Min": 0, "Max": 2 }, "Chance": 0.5, "Gate": "Puzzle" },
                            { "Role": "Secret" } ],
              "Bounds": { "X": 256, "Z": 300, "Entry": { "Y": 120 } },
              "MaxTries": 12
            }
            """;

    @Test
    void everyLeafRoundTripsThroughTheCodec() throws IOException {
        DungeonLayoutAsset read = decode("Stone_Run", STONE_RUN, null);
        assertStoneRun(read.toPlan());

        DungeonLayoutAsset again = decode("Stone_Run",
                DungeonLayoutAsset.CODEC.encode(read, new ExtraInfo()).toJson(), null);
        assertEquals(read.toPlan(), again.toPlan(), "written back, the file reads the same");
        ObjectSchema schema = (ObjectSchema) DungeonLayoutAsset.CODEC.toSchema(new SchemaContext());
        assertNotNull(schema.getProperties().get("Path"), "the schema the Asset Editor loads declares the path");
        assertNotNull(schema.getProperties().get("Bounds"), "and the bounds");
    }

    @Test
    void anEmptyFileReadsEveryDefault() throws IOException {
        LayoutPlan bare = decode("Bare", "{}", null).toPlan();
        assertEquals("bare", bare.id());
        assertTrue(bare.roomTags().isEmpty(), "no Rooms.Tags: every room may be placed");
        assertTrue(bare.strata().isEmpty(), "no Strata: rooms are not filtered by stratum");
        assertTrue(bare.path().isEmpty());
        assertNull(bare.links(), "no Links: beats join directly");
        assertTrue(bare.branches().isEmpty());
        assertEquals(LayoutPlan.DEFAULT_BOUNDS, bare.bounds(), "Bounds default to 512 by 128 by 512");
        assertNull(bare.entry());
        assertEquals(new Cell(256, 64, 256), bare.entryCell(), "the entry defaults to the bounds' centre");
        assertTrue(bare.entryInBounds());
        assertEquals(LayoutPlan.DEFAULT_MAX_TRIES, bare.maxTries(), "MaxTries defaults to 64");
    }

    @Test
    void aChildKeepsWhatItDoesNotRestate() throws IOException {
        DungeonLayoutAsset parent = decode("Stone_Run", STONE_RUN, null);
        LayoutPlan child = decode("Stone_Run_Quick", "{ \"MaxTries\": 8 }", parent).toPlan();
        LayoutPlan base = parent.toPlan();
        assertEquals("stone_run_quick", child.id());
        assertEquals(8, child.maxTries());
        assertEquals(base.roomTags(), child.roomTags());
        assertEquals(base.strata(), child.strata());
        assertEquals(base.path(), child.path());
        assertEquals(base.links(), child.links());
        assertEquals(base.branches(), child.branches());
        assertEquals(base.bounds(), child.bounds());
        assertEquals(base.entry(), child.entry());
    }

    private static void assertStoneRun(@Nonnull LayoutPlan plan) {
        assertEquals("stone_run", plan.id(), "the id is folded");
        assertEquals(List.of("zc_kit_stone"), plan.roomTags());
        assertEquals(List.of(new Stratum(List.of("ruins", "crypt"), "Rock_Stone"), new Stratum(List.of("void"), null)),
                plan.strata(), "tags fold; a stratum's fill block is trimmed, never folded");
        assertEquals(List.of(new Beat("entry", Count.ONE, -1), new Beat("combat", new Count(2, 3), -1),
                        new Beat("puzzle", new Count(2, 2), -1), new Beat("boss", Count.ONE, 1)),
                plan.path(), "an unauthored Count is 1, an unauthored Max is Min, an unauthored Stratum the previous");
        assertEquals(new Links("corridor", new Count(0, 2)), plan.links());
        assertEquals(List.of(new Branch("treasure", new Count(0, 2), 0.5, "puzzle"),
                new Branch("secret", Count.ONE, 1, null)), plan.branches(), "Chance defaults to 1, Gate to none");
        assertEquals(new Cell(256, 128, 300), plan.bounds(), "an unauthored axis of Bounds is its default");
        assertEquals(new Cell(128, 120, 150), plan.entry(), "an unauthored axis of Entry is the box's centre");
        assertTrue(plan.entryInBounds());
        assertEquals(12, plan.maxTries());
    }

    @Nonnull
    private static DungeonLayoutAsset decode(@Nonnull String id, @Nonnull String json,
            @Nullable DungeonLayoutAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(DungeonLayoutAsset.class, id,
                parent == null ? null : parent.getId());
        return DungeonLayoutAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }
}
