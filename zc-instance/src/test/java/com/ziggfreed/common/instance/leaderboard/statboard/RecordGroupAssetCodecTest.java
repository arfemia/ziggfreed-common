package com.ziggfreed.common.instance.leaderboard.statboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * A Records group file's codec: every leaf reads as written, is written back and reads the same, an empty file reads
 * every documented default, and under {@code Parent} a child keeps every leaf it does not restate. Untagged: nothing
 * here builds an engine item.
 */
class RecordGroupAssetCodecTest {

    private static final String SEASONAL = """
            { "NameKey": " mypack.group.seasonal ", "Order": 10, "Icon": "Plant_Crop_Pumpkin_Block" }
            """;

    @Test
    void everyLeafRoundTripsThroughTheCodec() throws IOException {
        RecordGroupAsset read = decode("Seasonal", SEASONAL, null);
        assertSeasonal(read);

        RecordGroupAsset again = decode("Seasonal", RecordGroupAsset.CODEC.encode(read, new ExtraInfo()).toJson(), null);
        assertSeasonal(again);
        assertNotNull(RecordGroupAsset.CODEC.toSchema(new SchemaContext()).getProperties().get("Order"),
                "the schema the Asset Editor loads declares the leaves");
    }

    @Test
    void anEmptyFileReadsEveryDefault() throws IOException {
        RecordGroup bare = decode("Bare", "{}", null).toGroup("Bare");
        assertEquals("bare", bare.id(), "a group's id is its file's, lower-cased");
        assertNull(bare.nameKey(), "no NameKey: the segment shows the id");
        assertEquals(0, bare.order());
        assertNull(bare.icon());
    }

    @Test
    void aChildKeepsWhatItDoesNotRestate() throws IOException {
        RecordGroupAsset parent = decode("Seasonal", SEASONAL, null);
        RecordGroup child = decode("Harvest", "{ \"Order\": 12 }", parent).toGroup("Harvest");
        assertEquals("mypack.group.seasonal", child.nameKey());
        assertEquals(12, child.order());
        assertEquals("Plant_Crop_Pumpkin_Block", child.icon());
    }

    private static void assertSeasonal(@Nonnull RecordGroupAsset read) {
        RecordGroup group = read.toGroup(read.getId());
        assertEquals("seasonal", group.id());
        assertEquals("mypack.group.seasonal", group.nameKey(), "the key is read trimmed");
        assertEquals(10, group.order());
        assertEquals("Plant_Crop_Pumpkin_Block", group.icon());
    }

    @Nonnull
    private static RecordGroupAsset decode(@Nonnull String id, @Nonnull String json, @Nullable RecordGroupAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(RecordGroupAsset.class, id,
                parent == null ? null : parent.getId());
        return RecordGroupAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }
}
