package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.progress.asset.ContentTextAsset;

/**
 * The asset-driven factor NAMING walk: several files may address one factor, the most specific
 * claim wins, keys are used exactly as authored, and a file that only names never touches the
 * value side.
 */
class FactorNamesTest {

    /** Nothing is shipped unless a test says so. */
    private static final Predicate<String> NO_KEYS = key -> false;

    @AfterEach
    void clearTheProcessWideConfig() {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of());
    }

    private static ContentTextAsset title(String key) {
        return ContentTextAsset.of(key, null, null);
    }

    @Test
    void aNamingOnlyFileNamesACodeRegisteredFactorWithoutShadowingItsValue() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:rank", ctx -> 7.0);
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of(
                "any_file_name", DerivedFactorAsset.of("any_file_name", null, "yourmod:rank", null,
                        title("yourmod.rank.name"), null)));

        assertEquals(7.0, registry.resolve("yourmod:rank", FactorContext.builder().build()),
                "the value still comes from the registered provider - a naming overlay never "
                        + "registers a reading");
        assertNull(DerivedFactorConfig.getInstance().formulaFor("yourmod:rank"),
                "and the config defines no value for the id it only names");

        Message name = FactorNames.name("yourmod:rank", null,
                DerivedFactorConfig.getInstance().all(), key -> true);
        assertEquals("yourmod.rank.name", name.getMessageId(),
                "the authored key reaches the client EXACTLY as written - nothing prepends a "
                        + "namespace to it");
    }

    @Test
    void overlaysComposeMostSpecificFirst() {
        // Two mods each ship an overlay on the SAME shared factor: one claims a single param
        // outright, the other covers a family through a pattern.
        Map<String, DerivedFactorAsset> files = Map.of(
                "a_mods_overlay", DerivedFactorAsset.of("a_mods_overlay", null, "hytale:stat", null,
                        null, DerivedFactorAsset.ParamNames.of(null,
                                Map.of("Special_Channel", "amod.special.name"))),
                "b_mods_overlay", DerivedFactorAsset.of("b_mods_overlay", null, "hytale:stat", null,
                        title("bmod.stat.generic"),
                        DerivedFactorAsset.ParamNames.of("bmod.stats.{param}.name", null)));
        Set<String> shipped = Set.of("amod.special.name", "bmod.stats.Fame.name", "bmod.stat.generic");

        assertEquals("amod.special.name",
                FactorNames.name("hytale:stat", "Special_Channel", files, shipped::contains).getMessageId(),
                "an exact per-param claim outranks every pattern");
        assertEquals("bmod.stats.Fame.name",
                FactorNames.name("hytale:stat", "Fame", files, shipped::contains).getMessageId(),
                "a pattern answers for the params whose resolved key it actually ships");
        assertEquals("bmod.stat.generic",
                FactorNames.name("hytale:stat", "Something_Unnamed", files, shipped::contains).getMessageId(),
                "a param nobody names falls to the factor's own bare name");
    }

    @Test
    void aPatternWhoseResolvedKeyIsNotShippedIsSkippedNeverPrinted() {
        Map<String, DerivedFactorAsset> files = Map.of(
                "overlay", DerivedFactorAsset.of("overlay", null, "hytale:stat", null, null,
                        DerivedFactorAsset.ParamNames.of("yourmod.stats.{param}.name", null)));

        assertNull(FactorNames.name("hytale:stat", "Nobody_Shipped_This", files, NO_KEYS),
                "a pattern speaks for a whole family, most of which is somebody else's to name - a "
                        + "miss walks on rather than painting a raw key");
    }

    @Test
    void thePatternTransformsBridgeAChannelSpellingToAnExistingKeyFamily() {
        // One overlay line names a whole technical channel family off keys that never carried the
        // prefix: strip runs first, the case fold second, and only then does the param drop in.
        Map<String, DerivedFactorAsset> files = Map.of(
                "overlay", DerivedFactorAsset.of("overlay", null, "hytale:stat", null, null,
                        DerivedFactorAsset.ParamNames.of("yourmod.skill.{param}",
                                "MMO_Level_", DerivedFactorAsset.ParamNames.CASE_LOWER, null)));

        assertEquals("yourmod.skill.mining",
                FactorNames.name("hytale:stat", "MMO_Level_MINING", files,
                        "yourmod.skill.mining"::equals).getMessageId(),
                "strip before substitution, case after the strip");
        assertNull(FactorNames.name("hytale:stat", "MMO_Level_MAGIC", files,
                        "yourmod.skill.mining"::equals),
                "a transformed key that is not shipped is still SKIPPED per the walk rule");
        assertEquals("yourmod.skill.somethingelse",
                FactorNames.name("hytale:stat", "SomethingElse", files,
                        "yourmod.skill.somethingelse"::equals).getMessageId(),
                "a param that does not carry the prefix is left alone (the case fold still runs)");
    }

    @Test
    void eachPatternTransformStandsAlone() {
        Map<String, DerivedFactorAsset> stripOnly = Map.of(
                "overlay", DerivedFactorAsset.of("overlay", null, "hytale:stat", null, null,
                        DerivedFactorAsset.ParamNames.of("yourmod.s.{param}", "MMO_", null, null)));
        assertEquals("yourmod.s.Fame",
                FactorNames.name("hytale:stat", "MMO_Fame", stripOnly,
                        "yourmod.s.Fame"::equals).getMessageId(),
                "no Case authored substitutes the stripped param exactly as spelled");

        Map<String, DerivedFactorAsset> caseOnly = Map.of(
                "overlay", DerivedFactorAsset.of("overlay", null, "hytale:stat", null, null,
                        DerivedFactorAsset.ParamNames.of("yourmod.s.{param}", null,
                                DerivedFactorAsset.ParamNames.CASE_UPPER, null)));
        assertEquals("yourmod.s.FAME",
                FactorNames.name("hytale:stat", "fame", caseOnly,
                        "yourmod.s.FAME"::equals).getMessageId(),
                "no StripPrefix authored folds the whole param");
    }

    @Test
    void theTransformsNeverTouchABespokeKeysEntry() {
        Map<String, DerivedFactorAsset> files = Map.of(
                "overlay", DerivedFactorAsset.of("overlay", null, "hytale:stat", null, null,
                        DerivedFactorAsset.ParamNames.of("yourmod.skill.{param}",
                                "MMO_Level_", DerivedFactorAsset.ParamNames.CASE_LOWER,
                                Map.of("MMO_CombatLevel", "yourmod.combat.name"))));

        assertEquals("yourmod.combat.name",
                FactorNames.name("hytale:stat", "MMO_CombatLevel", files, key -> true).getMessageId(),
                "a Keys entry matches the requirement's Param as authored - its author already "
                        + "wrote the whole key by hand");
    }

    @Test
    void anExactParamClaimNarrowsItselfToThatParamAlone() {
        Map<String, DerivedFactorAsset> files = Map.of(
                "pair", DerivedFactorAsset.of("pair", null, "yourmod:rank", "veteran",
                        title("yourmod.rank.veteran.name"), null));

        assertEquals("yourmod.rank.veteran.name",
                FactorNames.name("yourmod:rank", "veteran", files, key -> true).getMessageId());
        assertNull(FactorNames.name("yourmod:rank", "novice", files, key -> true),
                "a file narrowed to one param says nothing about the others");
        assertNull(FactorNames.name("yourmod:rank", null, files, key -> true),
                "nor about the bare factor");
    }

    @Test
    void aFactorNamedNowhereAnswersNothing() {
        assertNull(FactorNames.name("yourmod:unnamed", null, Map.of(), key -> true));
        assertNull(FactorNames.name(null, null));
        assertNull(FactorNames.name("  ", null));
    }

    @Test
    void aDefiningFileNamesItsOwnIdInline() {
        FactorFormula formula = FactorFormula.of(1.0, null, null);
        Map<String, DerivedFactorAsset> files = Map.of(
                "yourmod:gear_score", DerivedFactorAsset.of("yourmod:gear_score", formula, null, null,
                        title("yourmod.gear_score.name"), null));

        assertEquals("yourmod.gear_score.name",
                FactorNames.name("yourmod:gear_score", null, files, key -> true).getMessageId(),
                "a Formula file needs no Factor leaf - its filename is already the id it names");
    }

    @Test
    void anAuthoredExplicitKeyNobodyShipsStillAnswersAsItself() {
        Map<String, DerivedFactorAsset> files = Map.of(
                "overlay", DerivedFactorAsset.of("overlay", null, "yourmod:rank", null,
                        title("yourmod.rank.name"), null));

        Message name = FactorNames.name("yourmod:rank", null, files, NO_KEYS);
        assertEquals("yourmod.rank.name", name.getMessageId(),
                "a raw key on screen is traceable to the file that named it, where a blank is not");
    }

    @Test
    void aPlainFallbackNameAnswersWhereItsKeyIsNotShippedYet() {
        Map<String, DerivedFactorAsset> files = Map.of(
                "overlay", DerivedFactorAsset.of("overlay", null, "yourmod:rank", null,
                        ContentTextAsset.of(null, null, "Rank"), null));

        assertEquals("Rank", FactorNames.name("yourmod:rank", null, files, NO_KEYS).getRawText());
    }

    @Test
    void everyShippedOverlayDecodesAndNamesAPortableFactor() throws Exception {
        String[] files = {"Hytale_Stat", "Hytale_Held_Item", "Hytale_Held_Tag", "Hytale_Permission",
                "Hytale_Tool_Durability", "Hytale_Tool_Item_Level", "Hytale_Tool_Power",
                "Hytale_Tool_Quality", "Hytale_Tool_Tier"};
        for (String file : files) {
            String path = "/Server/ZiggfreedCommon/Factors/" + file + ".json";
            String json;
            try (var in = FactorNamesTest.class.getResourceAsStream(path)) {
                assertNotNull(in, "missing shipped overlay: " + path);
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            DerivedFactorAsset asset = DerivedFactorAsset.CODEC.decodeJsonAsset(
                    RawJsonReader.fromJsonString(json),
                    new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class, file, null)));
            assertTrue(asset.isOverlay(), path + " must target a factor through its Factor leaf");
            assertTrue(asset.namedFactorId().startsWith("hytale:"),
                    path + " names the portable vocabulary");
            assertTrue(asset.carriesNaming(), path + " must carry Text or ParamNames");
            assertNull(asset.getFormula(), path + " is a naming overlay, never a value definition");
        }
    }

    @Test
    void theLiveEntryPointReadsTheFoldedStore() {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of(
                "overlay", DerivedFactorAsset.of("overlay", null, "yourmod:rank", null,
                        ContentTextAsset.of(null, null, "Rank"), null)));

        assertEquals("Rank", FactorNames.name("yourmod:rank", null).getRawText());
        assertTrue(DerivedFactorConfig.getInstance().definedIds().isEmpty(),
                "a naming overlay is not a defined factor, so it never leaks into the vocabulary "
                        + "listing");
    }
}
