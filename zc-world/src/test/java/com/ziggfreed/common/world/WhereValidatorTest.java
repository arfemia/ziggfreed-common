package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * {@link WhereValidator}: the findings that are otherwise silent, and the "cannot tell" contract
 * that keeps a pre-boot audit quiet. Every fixture is authored here; nothing reads shipped content.
 */
class WhereValidatorTest {

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    // ==================== shape ====================

    @Test
    void anExcludeOnlyWhereIsAnError() {
        List<Finding> out = WhereValidator.validateSelector(
                WorldSelector.of(null, null, new String[]{"*Arena*"}), "some_asset.Where");

        assertEquals(List.of("EXCLUDE_ONLY"), codes(out));
        assertEquals(Severity.ERROR, out.get(0).severity(),
                "a Where that can never match is an error, not a remark - its content is invisible");
        assertEquals(WhereValidator.DOMAIN, out.get(0).domain());
    }

    @Test
    void aBlankEntryIsReportedOncePerAxis() {
        List<Finding> out = WhereValidator.validateSelector(
                WorldSelector.of(new String[]{"default", ""}, new String[]{" "}, null), "x.Where");

        assertEquals(List.of("BLANK_ENTRY", "BLANK_ENTRY"), codes(out));
        assertTrue(out.stream().allMatch(f -> f.severity() == Severity.WARNING));
    }

    @Test
    void aWellFormedWhereReportsNothing() {
        assertTrue(WhereValidator.validateSelector(
                WorldSelector.of(new String[]{"*Forgotten_Temple*"},
                        new String[]{"ForgottenTemple"}, new String[]{"*Arena*"}), "x.Where").isEmpty());
    }

    @Test
    void aNullWhereReportsNothing() {
        assertTrue(WhereValidator.validateSelector(null, "x.Where").isEmpty());
    }

    // ==================== describes a real world ====================

    @Test
    void aWhereMatchingNoLoadedWorldIsAWarning() {
        List<Finding> out = WhereValidator.validateAgainstWorlds(
                WorldSelector.of(new String[]{"my_server_world"}, null, null), "rule.Where",
                List.of(new WhereValidator.LoadedWorld("default", "Default")));

        assertEquals(List.of("MATCHES_NO_LOADED_WORLD"), codes(out));
        assertEquals(Severity.WARNING, out.get(0).severity(),
                "a Where aimed at an instance world is correct while no instance is running");
        assertTrue(out.get(0).message().contains("default"), "the message must name the real worlds");
    }

    @Test
    void aWhereMatchingAnyLoadedWorldReportsNothing() {
        assertTrue(WhereValidator.validateAgainstWorlds(
                WorldSelector.of(new String[]{"default"}, null, null), "rule.Where",
                List.of(new WhereValidator.LoadedWorld("default", "Default"))).isEmpty());
    }

    @Test
    void theGameplayConfigAxisAloneSatisfiesTheCheck() {
        assertTrue(WhereValidator.validateAgainstWorlds(
                WorldSelector.of(null, new String[]{"ForgottenTemple"}, null), "rule.Where",
                List.of(new WhereValidator.LoadedWorld("instance-Forgotten_Temple-8f2c1a",
                        "ForgottenTemple"))).isEmpty());
    }

    @Test
    void anEmptyWorldListMeansCannotTellAndReportsNothing() {
        assertTrue(WhereValidator.validateAgainstWorlds(
                WorldSelector.of(new String[]{"nowhere"}, null, null), "rule.Where", List.of()).isEmpty(),
                "an audit run before boot, or in a unit JVM, must not flag every file on the server");
    }

    @Test
    void aWhereWithNoPositiveAxisIsNotReportedTwice() {
        // The shape check already called it out as EXCLUDE_ONLY; reporting it again here would
        // double-count one mistake.
        assertTrue(WhereValidator.validateAgainstWorlds(
                WorldSelector.of(null, null, new String[]{"*Arena*"}), "rule.Where",
                List.of(new WhereValidator.LoadedWorld("default", "Default"))).isEmpty());
    }
}
