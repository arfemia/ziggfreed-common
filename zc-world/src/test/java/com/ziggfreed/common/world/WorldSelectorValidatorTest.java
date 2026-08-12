package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The findings that exist because these mistakes are SILENT at runtime: content bound to a
 * selector that can never match simply never appears, with no error anywhere.
 */
class WorldSelectorValidatorTest {

    private static boolean hasCode(List<Finding> issues, String code) {
        return issues.stream().anyMatch(i -> i.code().equals(code));
    }

    @Test
    void missingNamesIsAnError() {
        List<Finding> issues = WorldSelectorValidator.validate(
                new WorldSelectorDef("zc_primary", null, new String[]{"default"}, null));

        assertTrue(hasCode(issues, "MISSING_NAMES"));
        assertEquals(Severity.ERROR, issues.get(0).severity());
    }

    @Test
    void anEmptyNamesListIsAlsoMissing() {
        assertTrue(hasCode(WorldSelectorValidator.validate(
                new WorldSelectorDef("zc_primary", new String[0], new String[]{"default"}, null)),
                "MISSING_NAMES"));
    }

    @Test
    void aBlankNameEntryIsAnError() {
        List<Finding> issues = WorldSelectorValidator.validate(
                new WorldSelectorDef("zc_primary", new String[]{"primary", "  "},
                        new String[]{"default"}, null));

        assertTrue(hasCode(issues, "BLANK_NAME"));
    }

    @Test
    void namesWithNoPatternMatchesNothing() {
        List<Finding> issues = WorldSelectorValidator.validate(
                new WorldSelectorDef("mmo_temple", new String[]{"forgotten_temple"}, null, null));

        assertTrue(hasCode(issues, "MATCHES_NOTHING"));
        assertEquals(Severity.WARNING, issues.get(0).severity());
    }

    @Test
    void aWellFormedSelectorHasNoFindings() {
        assertTrue(WorldSelectorValidator.validate(new WorldSelectorDef("mmo_temple",
                new String[]{"forgotten_temple"}, new String[]{"*Forgotten_Temple*"},
                new String[]{"ForgottenTemple"})).isEmpty());
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
                WorldSelector.of(new String[]{"primary"}, null, null, new String[]{"instance"}),
                "mmo_hub.Where").isEmpty());
    }

    @Test
    void aNullSelectorProducesNoFindings() {
        assertTrue(WorldSelectorValidator.validateSelector(null, "mmo_hub.Where").isEmpty());
    }
}
