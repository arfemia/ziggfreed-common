package com.ziggfreed.common.instance.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The dungeon file's codec: every group and leaf reads as written, is written back and reads the same; an
 * empty file reads every documented default; and under {@code Parent} (which the owner file's entries decode
 * through) a child keeps every leaf of a group it does not restate. Untagged: nothing here builds an engine
 * item.
 */
class DungeonAssetCodecTest {

    private static final String CRYPT = """
            {
              "Instance": "MyPack_Crypt",
              "Enabled": false,
              "Share": { "PartyLocked": false, "MaxPlayers": 3, "AreasPerCopy": 2 },
              "Area": { "Prefab": "Server/Prefabs/MyPack/Crypt/Crypt_Main.prefab.json", "Spacing": 4096,
                        "EntryOffset": { "X": 1.5, "Y": 2, "Z": -3 } },
              "Run": { "TimeLimitSeconds": 900, "FailOnWipe": true },
              "Copy": { "EmptyCloseSeconds": 30 },
              "Difficulty": " Hard ",
              "Season": "Hallows_Eve",
              "Text": { "NameKey": "mypack.dungeon.crypt.name", "DescriptionKey": "mypack.dungeon.crypt.description" }
            }
            """;

    @Test
    void everyLeafRoundTripsThroughTheCodec() throws IOException {
        DungeonAsset read = decode("MyPack_Crypt", CRYPT, null);
        assertCrypt(read);

        DungeonAsset again = decode("MyPack_Crypt", DungeonAsset.CODEC.encode(read, new ExtraInfo()).toJson(), null);
        assertCrypt(again);
        assertNotNull(DungeonAsset.CODEC.toSchema(new SchemaContext()).getProperties().get("Share"),
                "the schema the Asset Editor loads declares the groups");
    }

    @Test
    void anEmptyFileReadsEveryDefault() throws IOException {
        DungeonAsset bare = decode("Bare", "{}", null);

        assertEquals(DungeonAsset.DEFAULT_TEMPLATE, bare.templateId());
        assertTrue(bare.isEnabled());
        assertTrue(bare.partyLocked());
        assertEquals(DungeonAsset.DEFAULT_MAX_PLAYERS, bare.maxPlayers());
        assertEquals(DungeonAsset.DEFAULT_AREAS_PER_COPY, bare.areasPerCopy());
        assertNull(bare.prefabPath());
        assertEquals(DungeonAsset.DEFAULT_SPACING, bare.spacing());
        assertEquals(0.0, bare.entryOffset().effectiveY());
        assertNull(bare.timeLimitSeconds(), "no time limit unless one is written");
        assertFalse(bare.failOnWipe());
        assertEquals(DungeonAsset.DEFAULT_EMPTY_CLOSE_SECONDS, bare.emptyCloseSeconds());
        assertEquals("", bare.difficulty(), "empty is normal");
        assertNull(bare.getSeason(), "no season is all year");
        assertNull(bare.nameKey());
        assertNull(bare.descriptionKey());
    }

    @Test
    void underParentAChildKeepsEveryLeafOfAGroupItDoesNotRestate() throws IOException {
        DungeonAsset parent = decode("MyPack_Crypt", CRYPT, null);
        DungeonAsset child = decode("MyPack_Crypt_Solo", """
                { "Enabled": true, "Share": { "AreasPerCopy": 1 }, "Run": { "TimeLimitSeconds": 0 } }
                """, parent);

        assertTrue(child.isEnabled(), "the restated leaf is the child's");
        assertEquals(1, child.areasPerCopy());
        assertEquals(3, child.maxPlayers(), "the group merges leaf by leaf");
        assertFalse(child.partyLocked());
        assertNull(child.timeLimitSeconds(), "zero is no time limit");
        assertTrue(child.failOnWipe());
        assertEquals("MyPack/Crypt/Crypt_Main.prefab.json", child.prefabPath(), "an unrestated group is inherited");
        assertEquals("Hallows_Eve", child.getSeason());
    }

    private static void assertCrypt(@Nonnull DungeonAsset crypt) {
        assertEquals("MyPack_Crypt", crypt.getId());
        assertEquals("MyPack_Crypt", crypt.templateId());
        assertFalse(crypt.isEnabled());
        assertFalse(crypt.isOpen(), "a switched-off dungeon is never open");
        assertFalse(crypt.partyLocked());
        assertEquals(3, crypt.maxPlayers());
        assertEquals(2, crypt.areasPerCopy());
        assertEquals("MyPack/Crypt/Crypt_Main.prefab.json", crypt.prefabPath(),
                "the path reads under Server/Prefabs/ whether or not the file writes that folder");
        assertEquals(4096, crypt.spacing());
        assertEquals(1.5, crypt.entryOffset().effectiveX());
        assertEquals(2.0, crypt.entryOffset().effectiveY());
        assertEquals(-3.0, crypt.entryOffset().effectiveZ());
        assertEquals(Integer.valueOf(900), crypt.timeLimitSeconds());
        assertTrue(crypt.failOnWipe());
        assertEquals(30, crypt.emptyCloseSeconds());
        assertEquals("Hard", crypt.difficulty());
        assertEquals("Hallows_Eve", crypt.getSeason());
        assertEquals("mypack.dungeon.crypt.name", crypt.nameKey());
        assertEquals("mypack.dungeon.crypt.description", crypt.descriptionKey());
        CopyRouter.Rules rules = crypt.routerRules("MyPack_Crypt");
        assertEquals("mypack_crypt", rules.dungeon(), "the router files a dungeon under its lower-cased id");
        assertEquals(30_000L, rules.emptyCloseMs());
    }

    @Nonnull
    private static DungeonAsset decode(@Nonnull String id, @Nonnull String json, @Nullable DungeonAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(DungeonAsset.class, id,
                parent == null ? null : parent.getId());
        return DungeonAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }
}
