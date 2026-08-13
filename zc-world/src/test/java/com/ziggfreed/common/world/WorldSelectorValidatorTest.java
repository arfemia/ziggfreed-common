package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;
import com.ziggfreed.common.world.WorldSelectorValidator.LoadedWorld;

/**
 * The findings that exist because these mistakes are SILENT at runtime: content bound to a
 * selector that can never match simply never appears, with no error anywhere.
 */
class WorldSelectorValidatorTest {

    private static boolean hasCode(List<Finding> issues, String code) {
        return issues.stream().anyMatch(i -> i.code().equals(code));
    }

    private static WorldSelectorDef def(String id, String[] names, String[] match,
            String[] gameplayConfig) {
        return new WorldSelectorDef(id, names, match, gameplayConfig, null);
    }

    @Test
    void missingNamesIsAnError() {
        List<Finding> issues = WorldSelectorValidator.validate(
                def("zc_default", null, new String[]{"default"}, null));

        assertTrue(hasCode(issues, "MISSING_NAMES"));
        assertEquals(Severity.ERROR, issues.get(0).severity());
    }

    @Test
    void anEmptyNamesListIsAlsoMissing() {
        assertTrue(hasCode(WorldSelectorValidator.validate(
                def("zc_default", new String[0], new String[]{"default"}, null)),
                "MISSING_NAMES"));
    }

    @Test
    void aBlankNameEntryIsAnError() {
        List<Finding> issues = WorldSelectorValidator.validate(
                def("zc_default", new String[]{"default", "  "}, new String[]{"default"}, null));

        assertTrue(hasCode(issues, "BLANK_NAME"));
    }

    @Test
    void namesWithNoPatternMatchesNothing() {
        List<Finding> issues = WorldSelectorValidator.validate(
                def("mmo_temple", new String[]{"forgotten_temple"}, null, null));

        assertTrue(hasCode(issues, "MATCHES_NOTHING"));
        assertEquals(Severity.WARNING, issues.get(0).severity());
    }

    @Test
    void aWellFormedSelectorHasNoFindings() {
        assertTrue(WorldSelectorValidator.validate(def("mmo_temple",
                new String[]{"forgotten_temple"}, new String[]{"*Forgotten_Temple*"},
                new String[]{"ForgottenTemple"})).isEmpty());
    }

    @Test
    void aBlankExcludeEntryOnAnAssetIsReported() {
        assertTrue(hasCode(WorldSelectorValidator.validate(new WorldSelectorDef("mmo_outdoors",
                        new String[]{"outdoor"}, new String[]{"*"}, null, new String[]{"arena", " "})),
                "BLANK_ENTRY"));
    }

    @Test
    void anExcludeOnlySelectorIsAnError() {
        List<Finding> issues = WorldSelectorValidator.validateSelector(
                WorldSelector.of(null, null, null, new String[]{"instance"}), "mmo_hub.Where");

        assertTrue(hasCode(issues, "EXCLUDE_ONLY"),
                "an ExcludeNames-only selector reads as a complement but matches nothing");
        assertEquals("mmo_hub.Where", issues.get(0).sourceId());
    }

    @Test
    void anExcludeAlongsideAPositiveAxisIsFine() {
        assertTrue(WorldSelectorValidator.validateSelector(
                WorldSelector.of(new String[]{"default"}, null, null, new String[]{"instance"}),
                "mmo_hub.Where").isEmpty());
    }

    @Test
    void aNullSelectorProducesNoFindings() {
        assertTrue(WorldSelectorValidator.validateSelector(null, "mmo_hub.Where").isEmpty());
    }

    // ==================== the shared unknown-name check ====================

    @Test
    void anUnknownNameIsAWarningNotAnError() {
        List<Finding> issues = WorldSelectorValidator.validateNames(
                new String[]{"forgotten_temple"}, "Where", "mmo_hub", Set.of("default", "any"));

        assertTrue(hasCode(issues, "UNKNOWN_SELECTOR_NAME"));
        assertEquals(Severity.WARNING, issues.get(0).severity(),
                "the mod that hands the name out may simply not be installed here");
        assertEquals("mmo_hub", issues.get(0).sourceId());
    }

    @Test
    void aKnownNameIsSilentAndTheCheckIsCaseInsensitive() {
        assertTrue(WorldSelectorValidator.validateNames(
                new String[]{" Default "}, "Where", "mmo_hub", Set.of("default")).isEmpty());
    }

    @Test
    void anEmptyVocabularyMeansCannotTellAndReportsNothing() {
        assertTrue(WorldSelectorValidator.validateNames(
                new String[]{"forgotten_temple"}, "Where", "mmo_hub", Set.of()).isEmpty());
        assertTrue(WorldSelectorValidator.validateNames(
                new String[]{"forgotten_temple"}, "Where", "mmo_hub", null).isEmpty());
    }

    // ==================== the describes-a-real-world check ====================

    @Test
    void aSelectorMatchingNoLoadedWorldIsReported() {
        List<Finding> issues = WorldSelectorValidator.validateAgainstWorlds(
                List.of(def("zc_default", new String[]{"default"}, new String[]{"default"}, null)),
                List.of(new LoadedWorld("my_server_world", null)));

        assertTrue(hasCode(issues, "MATCHES_NO_LOADED_WORLD"),
                "a renamed main world is exactly the misconfiguration this makes loud");
        assertEquals(Severity.WARNING, issues.get(0).severity());
        assertTrue(issues.get(0).message().contains("my_server_world"),
                "naming the real worlds is what makes the finding actionable");
    }

    @Test
    void aSelectorThatMatchesSomethingIsSilent() {
        assertTrue(WorldSelectorValidator.validateAgainstWorlds(
                List.of(def("zc_default", new String[]{"default"}, new String[]{"default"}, null)),
                List.of(new LoadedWorld("default", null))).isEmpty());
    }

    @Test
    void noLoadedWorldsMeansCannotTell() {
        assertTrue(WorldSelectorValidator.validateAgainstWorlds(
                List.of(def("zc_default", new String[]{"default"}, new String[]{"default"}, null)),
                List.of()).isEmpty(),
                "an audit before boot must not flag every selector on the server");
    }

    @Test
    void aSelectorAlreadyReportedAsUnmatchableIsNotReportedTwice() {
        assertFalse(hasCode(WorldSelectorValidator.validateAgainstWorlds(
                List.of(def("mmo_temple", new String[]{"forgotten_temple"}, null, null)),
                List.of(new LoadedWorld("default", null))), "MATCHES_NO_LOADED_WORLD"),
                "a selector with no pattern at all is MATCHES_NOTHING, a different and better finding");
    }

    @Test
    void anInlineSelectorIsCheckedAgainstTheLoadedWorldsToo() {
        List<WorldSelectorDef> pool =
                List.of(def("zc_default", new String[]{"default"}, new String[]{"default"}, null));

        assertTrue(WorldSelectorValidator.validateSelectorAgainstWorlds(
                WorldSelector.of(new String[]{"default"}, null, null, null), "mmo_hub.Where",
                List.of(new LoadedWorld("default", null)), pool).isEmpty());

        assertTrue(hasCode(WorldSelectorValidator.validateSelectorAgainstWorlds(
                        WorldSelector.of(new String[]{"default"}, null, null, null), "mmo_hub.Where",
                        List.of(new LoadedWorld("my_server_world", null)), pool),
                "MATCHES_NO_LOADED_WORLD"));
    }
}
