package com.ziggfreed.common.instance.portal;

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
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * A portal dungeon file's codec: every leaf and group reads as written, is written back and reads the same, an
 * empty file reads every documented default, and under {@code Parent} a child keeps every leaf it does not restate.
 * Untagged: nothing here builds an engine item.
 */
class PortalDungeonAssetCodecTest {

    private static final String DEEPWORKS = """
            {
              "Enabled": false,
              "NameKey": " server.portals.theDeepworks ",
              "Icon": "PortalKey_The_Deepworks",
              "Complete": { "Encounters": [" Goblin_Gobliterator_Encounter ", "", "goblin_gobliterator_encounter",
                                           "Goblin_Gobliterator_Encounter_Hard"] }
            }
            """;

    @Test
    void everyLeafRoundTripsThroughTheCodec() throws IOException {
        PortalDungeonAsset read = decode("The_Deepworks", DEEPWORKS, null);
        assertDeepworks(read);

        PortalDungeonAsset again = decode("The_Deepworks",
                PortalDungeonAsset.CODEC.encode(read, new ExtraInfo()).toJson(), null);
        assertDeepworks(again);
        assertNotNull(PortalDungeonAsset.CODEC.toSchema(new SchemaContext()).getProperties().get("Complete"),
                "the schema the Asset Editor loads declares the groups");
    }

    @Test
    void anEmptyFileReadsEveryDefault() throws IOException {
        PortalDungeonAsset bare = decode("Bare", "{}", null);
        assertTrue(bare.isEnabled(), "a file follows its dungeon unless it says otherwise");
        assertNull(bare.nameKey(), "no NameKey: the game's own name for the portal");
        assertNull(bare.icon());
        assertTrue(bare.completeEncounters().isEmpty(), "no Complete: nothing clears a run");
    }

    @Test
    void aChildKeepsWhatItDoesNotRestate() throws IOException {
        PortalDungeonAsset parent = decode("The_Deepworks", DEEPWORKS, null);
        PortalDungeonAsset child = decode("My_Deepworks", "{ \"Enabled\": true }", parent);
        assertTrue(child.isEnabled());
        assertEquals("server.portals.theDeepworks", child.nameKey());
        assertEquals(List.of("Goblin_Gobliterator_Encounter", "Goblin_Gobliterator_Encounter_Hard"),
                child.completeEncounters());
    }

    private static void assertDeepworks(@Nonnull PortalDungeonAsset read) {
        assertFalse(read.isEnabled());
        assertEquals("server.portals.theDeepworks", read.nameKey(), "the key is read trimmed");
        assertEquals("PortalKey_The_Deepworks", read.icon());
        assertEquals(List.of("Goblin_Gobliterator_Encounter", "Goblin_Gobliterator_Encounter_Hard"),
                read.completeEncounters(), "trimmed, a blank and a repeat (whatever its case) left out");
    }

    @Nonnull
    private static PortalDungeonAsset decode(@Nonnull String id, @Nonnull String json,
            @Nullable PortalDungeonAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(PortalDungeonAsset.class, id,
                parent == null ? null : parent.getId());
        return PortalDungeonAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }
}
