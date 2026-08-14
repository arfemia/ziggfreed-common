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

    // ==================== the default presentation ====================

    /**
     * How a kind says its rewards READ, and what one reward gets out of it.
     *
     * <p>The template cases are the load-bearing ones: a key spelled from a parameter is the whole
     * reason a kind can label a family of rewards none of which author anything, and a value written
     * the way a command reads it ({@code ARTILLERY}) has to reach a key written the way keys are
     * written ({@code artillery}) or the family never resolves.
     */
    @Nested
    class DefaultPresentation {

        static final String XP_KIND = """
                {
                  "Params": { "Skill": { "Default": "ALL" }, "Amount": { "Required": true } },
                  "Command": "mmoawardxp {player} {Skill} {Amount}",
                  "Presentation": {
                    "NameKey": "mymod.reward.xp.{Skill}",
                    "Icon": {
                      "ByParam": "Skill",
                      "Default": "Rock_Crystal_Iridescent_Small",
                      "Values": { "MINING": "Tool_Pickaxe_Crude", "ARTILLERY": "Weapon_Bomb" }
                    }
                  }
                }
                """;

        static RewardSpec xp(String skill) {
            return RewardSpec.of("Mmo_Xp", "Skill", skill);
        }

        @Test
        void everyLeafSurvivesADecode() throws Exception {
            RewardKindAsset kind = decodeRoot(XP_KIND, "Mmo_Xp");

            assertNotNull(kind.getPresentation());
            assertEquals("mymod.reward.xp.{Skill}", kind.getPresentation().getNameKey());

            RewardKindAsset.Icon icon = kind.getPresentation().getIcon();
            assertNotNull(icon);
            assertEquals("Skill", icon.getByParam());
            assertEquals("Rock_Crystal_Iridescent_Small", icon.getDefaultItem());
            assertEquals(2, icon.valuesOrEmpty().size());
        }

        @Test
        void aKindThatSaysNothingAnswersNothing() throws Exception {
            RewardKindAsset kind = decodeRoot("{ \"Command\": \"pay {player}\" }", "Plain");

            assertNull(kind.getPresentation());
            assertNull(kind.presentationNameKey(xp("MINING")));
            assertNull(kind.presentationIcon(xp("MINING")));
        }

        // ---------- the name template ----------

        @Test
        void aParameterFillsInLowerCasedSoAValueReachesAKey() throws Exception {
            RewardKindAsset kind = decodeRoot(XP_KIND, "Mmo_Xp");

            assertEquals("mymod.reward.xp.artillery", kind.presentationNameKey(xp("ARTILLERY")),
                    "the command reads ARTILLERY and the key is written artillery; the template bridges them");
        }

        @Test
        void anOmittedParameterFillsInFromItsDeclaredDefault() throws Exception {
            RewardKindAsset kind = decodeRoot(XP_KIND, "Mmo_Xp");

            assertEquals("mymod.reward.xp.all",
                    kind.presentationNameKey(RewardSpec.of("Mmo_Xp", "Amount", "500")));
        }

        @Test
        void aTemplateWithNoPlaceholdersIsTheKeyItself() throws Exception {
            RewardKindAsset kind = decodeRoot("""
                    { "Command": "x", "Presentation": { "NameKey": "mymod.reward.boost_token" } }
                    """, "Mmo_Boost_Token");

            assertEquals("mymod.reward.boost_token", kind.presentationNameKey(xp("MINING")));
        }

        @Test
        void aPlaceholderNamingNothingIsLeftStandingLikeInACommand() throws Exception {
            RewardKindAsset kind = decodeRoot("""
                    {
                      "Params": { "Skill": { } },
                      "Command": "x {Skill}",
                      "Presentation": { "NameKey": "mymod.reward.{Skil}.{Skill}" }
                    }
                    """, "Typo");

            assertEquals("mymod.reward.{Skil}.mining", kind.presentationNameKey(xp("MINING")),
                    "a typo has to turn up in the key that was asked for, not vanish into a blank segment");
        }

        // ---------- the icon rule ----------

        @Test
        void aMappedValueWinsAndAnUnmappedOneTakesTheDefault() throws Exception {
            RewardKindAsset kind = decodeRoot(XP_KIND, "Mmo_Xp");

            assertEquals("Weapon_Bomb", kind.presentationIcon(xp("ARTILLERY")));
            assertEquals("Rock_Crystal_Iridescent_Small", kind.presentationIcon(xp("TAMING")),
                    "a mapping table names only the cases worth distinguishing");
        }

        @Test
        void aValueIsMatchedWhateverTheCasing() throws Exception {
            RewardKindAsset kind = decodeRoot(XP_KIND, "Mmo_Xp");

            assertEquals("Tool_Pickaxe_Crude", kind.presentationIcon(xp("mining")));
        }

        @Test
        void withNoByParamEveryRewardDrawsTheDefault() throws Exception {
            RewardKindAsset kind = decodeRoot("""
                    {
                      "Params": { "ModId": { "Required": true } },
                      "Command": "x {ModId}",
                      "Presentation": { "Icon": { "Default": "Weapon_Staff_Crystal_Flame" } }
                    }
                    """, "Mmo_Ability_Mod");

            assertEquals("Weapon_Staff_Crystal_Flame",
                    kind.presentationIcon(RewardSpec.of("Mmo_Ability_Mod", "ModId", "Anything")));
        }

        @Test
        void aRuleWithNoDefaultAndNoMatchDrawsNothing() throws Exception {
            RewardKindAsset kind = decodeRoot("""
                    {
                      "Params": { "Skill": { } },
                      "Command": "x {Skill}",
                      "Presentation": { "Icon": { "ByParam": "Skill",
                                                  "Values": { "MINING": "Tool_Pickaxe_Crude" } } }
                    }
                    """, "Sparse");

            assertEquals("Tool_Pickaxe_Crude", kind.presentationIcon(xp("MINING")));
            assertNull(kind.presentationIcon(xp("TAMING")),
                    "no icon is a legitimate answer, and better than a wrong one");
        }

        // ---------- layering ----------

        @Test
        void aChildMergesPerLeafAndTheIconMapPerValue() throws Exception {
            RewardKindAsset parent = decodeRoot(XP_KIND, "Mmo_Xp");

            RewardKindAsset child = decode("""
                    { "Presentation": { "Icon": { "Values": { "TAMING": "Deco_Rope" } } } }
                    """, "Mypack_Xp", "Mmo_Xp", parent);

            assertEquals("mymod.reward.xp.{Skill}", child.getPresentation().getNameKey(),
                    "a child adding one icon must not lose the label it inherited");
            assertEquals("Deco_Rope", child.presentationIcon(xp("TAMING")));
            assertEquals("Weapon_Bomb", child.presentationIcon(xp("ARTILLERY")),
                    "adding one mapping keeps every mapping that was already there");
            assertEquals("Rock_Crystal_Iridescent_Small", child.presentationIcon(xp("SWORDS")),
                    "and keeps the fallback too");
        }

        @Test
        void aChildRetunesOneLeafAndKeepsTheOther() throws Exception {
            RewardKindAsset parent = decodeRoot(XP_KIND, "Mmo_Xp");

            RewardKindAsset child = decode("""
                    { "Presentation": { "NameKey": "mypack.xp.{Skill}" } }
                    """, "Mypack_Xp", "Mmo_Xp", parent);

            assertEquals("mypack.xp.mining", child.presentationNameKey(xp("MINING")));
            assertEquals("Tool_Pickaxe_Crude", child.presentationIcon(xp("MINING")),
                    "retuning the label leaves the icon rule alone");
        }

        @Test
        void aChildWritingNoPresentationInheritsTheWholeGroup() throws Exception {
            RewardKindAsset parent = decodeRoot(XP_KIND, "Mmo_Xp");

            RewardKindAsset child = decode("{ \"Command\": \"somethingelse {Skill}\" }",
                    "Mypack_Xp", "Mmo_Xp", parent);

            assertEquals("mymod.reward.xp.mining", child.presentationNameKey(xp("MINING")));
            assertEquals("Tool_Pickaxe_Crude", child.presentationIcon(xp("MINING")));
        }
    }
}
