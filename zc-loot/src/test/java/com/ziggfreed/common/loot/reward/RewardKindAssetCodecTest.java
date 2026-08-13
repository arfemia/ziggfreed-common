package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * What an authored reward kind decodes to, and what a {@code Parent} does to it.
 *
 * <p>The inheritance cases are the ones worth pinning, because the two fields behave DIFFERENTLY on
 * purpose and the documentation promises it: {@code Params} merges per parameter, {@code Command}
 * replaces. A child that retunes one parameter must not silently drop the rest.
 */
class RewardKindAssetCodecTest {

    static RewardKindAsset decode(String json, String id, String parentId, RewardKindAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(RewardKindAsset.class, id, parentId);
        return RewardKindAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    static RewardKindAsset decodeRoot(String json, String id) throws IOException {
        return decode(json, id, null, null);
    }

    // ==================== the whole shape ====================

    @Test
    void everyLeafSurvivesADecode() throws Exception {
        RewardKindAsset kind = decodeRoot("""
                {
                  "$Comment": "Awards skill experience.",
                  "Name": "Skill experience",
                  "Params": {
                    "$Comment": "Skill is a skill id; Amount is raw experience.",
                    "Skill":  { "Required": true },
                    "Amount": { "Required": true },
                    "Silent": { "Default": "false" }
                  },
                  "Command": "mmoawardxp {player} {Skill} {Amount} --silent={Silent}"
                }
                """, "Mmo_Xp");

        assertEquals("mmo_xp", kind.getId(), "the id is the filename, lower-cased");
        assertEquals("Mmo_Xp", kind.authoredId(), "the file's own spelling survives for logs and findings");
        assertEquals("mmoawardxp {player} {Skill} {Amount} --silent={Silent}", kind.getCommand());
        assertFalse(kind.isBlank());

        assertEquals(3, kind.paramsOrEmpty().size(), "a $-prefixed key is authoring metadata, not a parameter");
        assertTrue(kind.param("Skill").isRequired());
        assertFalse(kind.param("Skill").hasDefault());
        assertFalse(kind.param("Silent").isRequired());
        assertEquals("false", kind.param("Silent").getDefault());
    }

    @Test
    void aParameterIsFoundWhateverTheCasingAsked() throws Exception {
        RewardKindAsset kind = decodeRoot("""
                { "Params": { "Skill": { "Required": true } }, "Command": "x {Skill}" }
                """, "Mmo_Xp");

        assertTrue(kind.declares("skill"));
        assertTrue(kind.declares("SKILL"));
        assertNull(kind.param("Amount"));
    }

    @Test
    void anEmptyBodyDecodesToAKindThatSaysItPaysNothing() throws Exception {
        RewardKindAsset kind = decodeRoot("{ }", "Empty");
        assertTrue(kind.isBlank());
        assertTrue(kind.paramsOrEmpty().isEmpty());
    }

    // ==================== reading the template ====================

    @Nested
    class ReadingTheTemplate {

        @Test
        void everyPlaceholderIsListedOnceInWrittenOrder() throws Exception {
            RewardKindAsset kind = decodeRoot("""
                    { "Command": "pay {player} {Amount} then tell {player} {Amount} {uuid}" }
                    """, "Pay");

            assertEquals(List.of("player", "Amount", "uuid"), kind.commandPlaceholders());
        }

        @Test
        void theHeadIsTheFirstWordWithoutItsSlash() throws Exception {
            assertEquals("mmoawardxp",
                    decodeRoot("{ \"Command\": \"/mmoawardxp {player} 5\" }", "A").commandHead());
            assertEquals("give", decodeRoot("{ \"Command\": \"give {player} Bread\" }", "B").commandHead());
            assertEquals("", decodeRoot("{ }", "C").commandHead());
        }

        @Test
        void aDeclaredParameterTheCommandNeverWritesIsReportedAsUnused() throws Exception {
            RewardKindAsset kind = decodeRoot("""
                    {
                      "Params": { "Amount": { }, "Flavour": { } },
                      "Command": "pay {player} {Amount}"
                    }
                    """, "Pay");

            assertEquals(List.of("Flavour"), kind.unusedParams());
        }
    }

    // ==================== inheritance ====================

    @Nested
    class Inheritance {

        @Test
        void aChildInheritsBothFieldsWhenItWritesNeither() throws Exception {
            RewardKindAsset parent = decodeRoot("""
                    { "Params": { "Amount": { "Required": true } }, "Command": "pay {player} {Amount}" }
                    """, "Base");

            RewardKindAsset child = decode("{ }", "Variant", "Base", parent);

            assertEquals("pay {player} {Amount}", child.getCommand());
            assertNotNull(child.param("Amount"));
            assertTrue(child.param("Amount").isRequired());
        }

        @Test
        void paramsMergePerParameterSoARetuneKeepsItsSiblings() throws Exception {
            RewardKindAsset parent = decodeRoot("""
                    {
                      "Params": { "Amount": { "Required": true }, "Silent": { "Default": "false" } },
                      "Command": "pay {player} {Amount} --silent={Silent}"
                    }
                    """, "Base");

            RewardKindAsset child = decode("""
                    { "Params": { "Silent": { "Default": "true" } } }
                    """, "Variant", "Base", parent);

            assertEquals(2, child.paramsOrEmpty().size(),
                    "redeclaring one parameter must not discard the inherited ones");
            assertTrue(child.param("Amount").isRequired(), "the untouched parameter is inherited whole");
            assertEquals("true", child.param("Silent").getDefault(), "the named one is retuned");
        }

        @Test
        void commandReplacesWholesale() throws Exception {
            RewardKindAsset parent = decodeRoot("""
                    { "Params": { "Amount": { } }, "Command": "pay {player} {Amount}" }
                    """, "Base");

            RewardKindAsset child = decode("""
                    { "Command": "grant {player} {Amount}" }
                    """, "Variant", "Base", parent);

            assertEquals("grant {player} {Amount}", child.getCommand());
            assertNotNull(child.param("Amount"), "replacing the command leaves the schema alone");
        }
    }
}
