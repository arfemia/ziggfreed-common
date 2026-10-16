package com.ziggfreed.common.instance.dungeon;

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
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
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
                        "EntryOffset": { "X": 1.5, "Y": 2, "Z": -3 }, "EntryYaw": 180 },
              "Run": { "TimeLimitSeconds": 900, "FailOnWipe": true },
              "Copy": { "EmptyCloseSeconds": 30 },
              "Complete": { "Bosses": [" Crypt_Lord_Encounter ", "", "Crypt_Lord_Encounter", "Crypt_Lich_Encounter"],
                            "AllBosses": true },
              "Difficulty": " Hard ",
              "Season": "Hallows_Eve",
              "Text": { "NameKey": "mypack.dungeon.crypt.name", "DescriptionKey": "mypack.dungeon.crypt.description",
                        "GoalKey": " mypack.dungeon.crypt.goal " }
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
        assertEquals(0f, bare.entryYaw(), "no EntryYaw arrives facing north, as before");
        assertNull(bare.timeLimitSeconds(), "no time limit unless one is written");
        assertFalse(bare.failOnWipe());
        assertEquals(DungeonAsset.DEFAULT_EMPTY_CLOSE_SECONDS, bare.emptyCloseSeconds());
        assertEquals("", bare.difficulty(), "empty is normal");
        assertNull(bare.getSeason(), "no season is all year");
        assertNull(bare.nameKey());
        assertNull(bare.descriptionKey());
        assertNull(bare.goalKey(), "no GoalKey: the arrival line is built from Complete");
        assertTrue(bare.completeBosses().isEmpty(), "no Complete names no boss");
        assertFalse(bare.completeAllBosses(), "the first listed boss to fall clears, unless AllBosses says every one");
    }

    @Test
    void underParentAChildKeepsEveryLeafOfAGroupItDoesNotRestate() throws IOException {
        DungeonAsset parent = decode("MyPack_Crypt", CRYPT, null);
        DungeonAsset child = decode("MyPack_Crypt_Solo", """
                { "Enabled": true, "Share": { "AreasPerCopy": 1 }, "Run": { "TimeLimitSeconds": 0 },
                  "Complete": { "AllBosses": false } }
                """, parent);

        assertTrue(child.isEnabled(), "the restated leaf is the child's");
        assertEquals(1, child.areasPerCopy());
        assertEquals(3, child.maxPlayers(), "the group merges leaf by leaf");
        assertFalse(child.partyLocked());
        assertNull(child.timeLimitSeconds(), "zero is no time limit");
        assertTrue(child.failOnWipe());
        assertEquals("MyPack/Crypt/Crypt_Main.prefab.json", child.prefabPath(), "an unrestated group is inherited");
        assertEquals("Hallows_Eve", child.getSeason());
        assertFalse(child.completeAllBosses(), "the restated leaf is the child's");
        assertEquals(List.of("Crypt_Lord_Encounter", "Crypt_Lich_Encounter"), child.completeBosses(),
                "Complete merges leaf by leaf too");
    }

    @Test
    void entryCostReadsWrittenBackAndInheritsLeafByLeaf() throws IOException {
        DungeonAsset bare = decode("Bare", "{}", null);
        assertTrue(bare.entryCostItems().isEmpty(), "no EntryCost is free");
        assertNull(bare.entryCostHintKey());
        assertTrue(bare.entryCostConfirm(), "the confirm screen is on unless a file turns it off");
        assertNull(bare.entryCostConfirmKey(), "no ConfirmKey: the button reads the library's Confirm");

        DungeonAsset priced = decode("MyPack_Crypt", """
                { "EntryCost": { "Items": [ { "Item": " MyPack_Crypt_Key " }, { "Item": "" },
                                            { "Item": "MyPack_Bone", "Count": 3 }, { "Item": "MyPack_Crypt_Key", "Count": 0 } ],
                                 "HintKey": " mypack.dungeon.crypt.key_hint ", "Confirm": false,
                                 "ConfirmKey": " mypack.dungeon.crypt.unlock " } }
                """, null);
        List<DungeonEntryCost.Item> expected = List.of(new DungeonEntryCost.Item("MyPack_Crypt_Key", 2),
                new DungeonEntryCost.Item("MyPack_Bone", 3));
        assertEquals(expected, priced.entryCostItems(),
                "ids read trimmed, a blank entry is dropped, a missing or low Count is 1, and repeats add up");
        assertEquals("mypack.dungeon.crypt.key_hint", priced.entryCostHintKey());
        assertFalse(priced.entryCostConfirm());
        assertEquals("mypack.dungeon.crypt.unlock", priced.entryCostConfirmKey());

        DungeonAsset again = decode("MyPack_Crypt", DungeonAsset.CODEC.encode(priced, new ExtraInfo()).toJson(), null);
        assertEquals(expected, again.entryCostItems(), "the group is written back and reads the same");
        assertFalse(again.entryCostConfirm());
        assertEquals("mypack.dungeon.crypt.unlock", again.entryCostConfirmKey());

        DungeonAsset child = decode("MyPack_Crypt_Hard", """
                { "EntryCost": { "HintKey": "mypack.dungeon.crypt_hard.key_hint" } }
                """, priced);
        assertEquals(expected, child.entryCostItems(), "an unrestated Items leaf is inherited");
        assertEquals("mypack.dungeon.crypt_hard.key_hint", child.entryCostHintKey());
        assertFalse(child.entryCostConfirm(), "an unrestated Confirm is inherited too");
        assertNotNull(DungeonAsset.CODEC.toSchema(new SchemaContext()).getProperties().get("EntryCost"),
                "the schema the Asset Editor loads declares the group");
    }

    @Test
    void entryCostKeysReadTrimmedWrittenBackAndReplacedWholeUnderParent() throws IOException {
        assertTrue(decode("Bare", "{}", null).entryCostKeys().isEmpty(), "no Keys: only Items pays the way in");

        DungeonAsset keyed = decode("MyPack_Crypt", """
                { "EntryCost": { "Items": [ { "Item": "MyPack_Crypt_Key" } ],
                                 "Keys": [ " MyPack_Master_Key ", "", "MyPack_Master_Key", "MyPack_Skeleton_Key" ] } }
                """, null);
        List<String> expected = List.of("MyPack_Master_Key", "MyPack_Skeleton_Key");
        assertEquals(expected, keyed.entryCostKeys(), "ids read trimmed, blanks and repeats dropped, in order");

        DungeonAsset again = decode("MyPack_Crypt", DungeonAsset.CODEC.encode(keyed, new ExtraInfo()).toJson(), null);
        assertEquals(expected, again.entryCostKeys(), "the leaf is written back and reads the same");

        DungeonAsset child = decode("MyPack_Crypt_Hard", """
                { "EntryCost": { "Items": [ { "Item": "MyPack_Bone" } ] } }
                """, keyed);
        assertEquals(expected, child.entryCostKeys(), "an unrestated Keys leaf is inherited");
        DungeonAsset unkeyed = decode("MyPack_Crypt_Hard", """
                { "EntryCost": { "Keys": [] } }
                """, keyed);
        assertTrue(unkeyed.entryCostKeys().isEmpty(), "a restated Keys replaces the inherited list whole");

        ArraySchema keys = (ArraySchema) DungeonAsset.EntryCost.CODEC.toSchema(new SchemaContext()).getProperties()
                .get("Keys");
        assertEquals("Item", ((StringSchema) keys.getItems()).getHytaleAssetRef(),
                "each key offers the Asset Editor's item picker");
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
        assertEquals(180f, crypt.entryYaw());
        assertEquals(Integer.valueOf(900), crypt.timeLimitSeconds());
        assertTrue(crypt.failOnWipe());
        assertEquals(30, crypt.emptyCloseSeconds());
        assertEquals("Hard", crypt.difficulty());
        assertEquals("Hallows_Eve", crypt.getSeason());
        assertEquals("mypack.dungeon.crypt.name", crypt.nameKey());
        assertEquals("mypack.dungeon.crypt.description", crypt.descriptionKey());
        assertEquals("mypack.dungeon.crypt.goal", crypt.goalKey(), "GoalKey reads trimmed");
        assertEquals(List.of("Crypt_Lord_Encounter", "Crypt_Lich_Encounter"), crypt.completeBosses(),
                "Bosses reads trimmed, blanks and repeats dropped, in order");
        assertTrue(crypt.completeAllBosses());
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
