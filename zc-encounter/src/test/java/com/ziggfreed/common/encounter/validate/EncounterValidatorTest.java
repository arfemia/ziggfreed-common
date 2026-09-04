package com.ziggfreed.common.encounter.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingCodecTest;
import com.ziggfreed.common.encounter.asset.EncounterParticipationAsset;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/** The mistakes an author will actually make, each reported once under a stable code. */
class EncounterValidatorTest {

    private static EncounterScriptScan script(String id, boolean spawnable, List<String> signals, Set<String> slots,
            boolean collector, int defeatWithoutClear) {
        return new EncounterScriptScan(id, spawnable, signals, slots, collector, defeatWithoutClear == 0,
                defeatWithoutClear, 0);
    }

    @Test
    void aBlockingListUnderAOnceSensorIsAWarning() {
        EncounterScriptScan scan = new EncounterScriptScan("Boss", true, List.of("zc:engaged"), Set.of("Boss"), true,
                true, 0, 1);
        Finding finding = find(EncounterValidator.auditScript(scan), EncounterValidator.ONCE_BLOCKS_LIST);
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals("Boss", finding.sourceId());
        assertFalse(has(EncounterValidator.auditScript(script("Clean", true, List.of("zc:engaged"), Set.of("Boss"),
                true, 0)), EncounterValidator.ONCE_BLOCKS_LIST));
    }

    private static boolean has(List<Finding> findings, String code) {
        return findings.stream().anyMatch(f -> f.code().equals(code));
    }

    private static Finding find(List<Finding> findings, String code) {
        return findings.stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void aMistypedReservedMomentIsNamed() {
        EncounterScriptScan scan = script("Boss", true, List.of("zc:engage", "zc:phases:Two", "zc:shrine:lit"),
                Set.of("Boss"), true, 0);
        List<Finding> findings = EncounterValidator.auditScript(scan);
        assertEquals(2, findings.stream().filter(f -> f.code().equals(EncounterValidator.UNKNOWN_MOMENT)).count());
        assertEquals(Severity.WARNING, find(findings, EncounterValidator.UNKNOWN_MOMENT).severity());
        assertNull(EncounterValidator.nearestReserved("shrine"), "the author's own beat is its own thing");
        assertEquals("engaged", EncounterValidator.nearestReserved("engage"));
    }

    @Test
    void aDefeatBeatWithoutTheBarClearIsAnError() {
        EncounterScriptScan scan = script("Boss", true, List.of("zc:defeated"), Set.of("Boss"), true, 1);
        Finding finding = find(EncounterValidator.auditScript(scan), EncounterValidator.DEFEAT_WITHOUT_BAR_CLEAR);
        assertEquals(Severity.ERROR, finding.severity());
        assertEquals("Boss", finding.sourceId());
    }

    @Test
    void aPhaseWithNoStateIsAnError() {
        EncounterScriptScan scan = script("Boss", true, List.of("zc:phase"), Set.of(), false, 0);
        assertTrue(has(EncounterValidator.auditScript(scan), EncounterValidator.PHASE_WITHOUT_STATE));
    }

    @Test
    void aBindingIsReadAgainstItsScript() throws IOException {
        EncounterBindingAsset stray = EncounterBindingCodecTest.binding("{\"EncounterAsset\": \"Missing\"}", "stray", null);
        EncounterBindingAsset abstractBase = EncounterBindingCodecTest.binding("{\"EncounterAsset\": \"Base\"}", "base", null);
        EncounterBindingAsset wrongSlot = EncounterBindingCodecTest.binding(
                "{\"EncounterAsset\": \"Boss\", \"Subject\": {\"TargetSlot\": \"Champion\"}}", "wrongslot", null);
        EncounterBindingAsset again = EncounterBindingCodecTest.binding("{\"EncounterAsset\": \"Boss\"}", "again", null);
        Map<String, EncounterScriptScan> scripts = Map.of(
                "Boss", script("Boss", true, List.of("zc:engaged", "zc:defeated"), Set.of("Boss"), true, 0),
                "Base", script("Base", false, List.of("zc:engaged"), Set.of("Boss"), true, 0),
                "Loose", script("Loose", true, List.of("zc:engaged"), Set.of(), false, 0));
        List<Finding> findings = EncounterValidator.validate(scripts, List.of(stray, abstractBase, wrongSlot, again),
                List.of(), null);
        assertTrue(has(findings, EncounterValidator.BINDING_UNKNOWN_SCRIPT));
        assertTrue(has(findings, EncounterValidator.BINDING_NOT_SPAWNABLE));
        assertTrue(has(findings, EncounterValidator.SUBJECT_SLOT_UNKNOWN));
        assertTrue(has(findings, EncounterValidator.DUPLICATE_BINDING));
        Finding unbound = find(findings, EncounterValidator.SCRIPT_UNBOUND);
        assertEquals("Loose", unbound.sourceId());
        assertEquals(Severity.INFO, unbound.severity());
        assertFalse(findings.stream().anyMatch(f -> f.code().equals(EncounterValidator.SCRIPT_UNBOUND)
                && f.sourceId().equals("Base")), "an abstract base is never expected to be bound");
    }

    @Test
    void presenceWeightedOnAScriptWithNoCollectorIsAWarning() throws IOException {
        EncounterBindingAsset row = EncounterBindingCodecTest.binding(
                "{\"EncounterAsset\": \"Boss\", \"Participation\": {\"Presence\": 0.1}}", "row", null);
        EncounterParticipationAsset rule = EncounterBindingCodecTest.rule("{\"Presence\": 0.1}", "zc_default");
        Map<String, EncounterScriptScan> scripts = Map.of(
                "Boss", script("Boss", true, List.of("zc:engaged"), Set.of("Boss"), false, 0));
        assertTrue(has(EncounterValidator.validate(scripts, List.of(row), List.of(), null),
                EncounterValidator.PRESENCE_WITHOUT_COLLECTOR), "from the row's own override");
        EncounterBindingAsset plain = EncounterBindingCodecTest.binding("{\"EncounterAsset\": \"Boss\"}", "plain", null);
        assertTrue(has(EncounterValidator.validate(scripts, List.of(plain), List.of(rule), null),
                EncounterValidator.PRESENCE_WITHOUT_COLLECTOR), "from the catch-all rule");
        assertFalse(has(EncounterValidator.validate(scripts, List.of(plain), List.of(), null),
                EncounterValidator.PRESENCE_WITHOUT_COLLECTOR), "the posture weighs no presence");
    }

    @Test
    void anUnknownLootTableIsAWarningOnlyWhenSomethingCanAnswer() throws IOException {
        EncounterBindingAsset row = EncounterBindingCodecTest.binding(
                "{\"EncounterAsset\": \"Boss\", \"Loot\": {\"OnDefeat\": {\"Lootables\": [\"nowhere\"]}}}", "row", null);
        Map<String, EncounterScriptScan> scripts = Map.of(
                "Boss", script("Boss", true, List.of("zc:engaged"), Set.of("Boss"), true, 0));
        assertTrue(has(EncounterValidator.validate(scripts, List.of(row), List.of(), id -> false),
                EncounterValidator.UNKNOWN_LOOTABLE));
        assertFalse(has(EncounterValidator.validate(scripts, List.of(row), List.of(), null),
                EncounterValidator.UNKNOWN_LOOTABLE));
    }

    @Test
    void aRuleOutsideItsRangeIsAnError() throws IOException {
        EncounterParticipationAsset rule = EncounterBindingCodecTest.rule("{\"MinShare\": 1.5, \"Match\": \"a*b\"}", "r");
        List<Finding> findings = EncounterValidator.validate(Map.of(), List.of(), List.of(rule), null);
        assertEquals(Severity.ERROR, find(findings, EncounterValidator.RULE_MIN_SHARE_OUT_OF_RANGE).severity());
        assertTrue(has(findings, EncounterValidator.RULE_BAD_MATCH));
    }

    @Test
    void cleanContentReportsNothing() throws IOException {
        EncounterBindingAsset row = EncounterBindingCodecTest.binding("{\"EncounterAsset\": \"Boss\"}", "row", null);
        Map<String, EncounterScriptScan> scripts = Map.of(
                "Boss", script("Boss", true, List.of("zc:engaged", "zc:defeated"), Set.of("Boss"), true, 0));
        assertTrue(EncounterValidator.validate(scripts, List.of(row), List.of(), null).isEmpty());
    }

    @Test
    void aRoleReferenceThatResolvesToAScriptIsReportedAgainstWhatNamesIt() throws IOException {
        EncounterBindingAsset row = EncounterBindingCodecTest.binding("{\"EncounterAsset\": \"Warden\"}", "row", null);
        Map<String, EncounterScriptScan> scripts = Map.of(
                "Warden", script("Warden", true, List.of("zc:engaged", "zc:defeated"), Set.of("Boss"), true, 0));
        List<EncounterValidator.RoleReference> references = List.of(
                new EncounterValidator.RoleReference("warden", "spawn marker", "Warden_Roost"),
                new EncounterValidator.RoleReference("Guide", "placement", "grove_guide"));
        List<Finding> findings = EncounterValidator.validate(scripts, List.of(row), List.of(), null, references, null);
        Finding finding = find(findings, EncounterValidator.SCRIPT_ID_IS_ROLE_ID);
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals("Warden_Roost", finding.sourceId(), "the marker that names the lost role is the source");
        assertTrue(finding.message().contains("spawn marker") && finding.message().contains("'Warden'"),
                "the finding says what named the role and which script took its name");
        assertEquals(1, findings.stream().filter(f -> f.code().equals(EncounterValidator.SCRIPT_ID_IS_ROLE_ID)).count(),
                "a role no script is named after is not reported");
        assertFalse(has(EncounterValidator.validate(scripts, List.of(row), List.of(), null, List.of(), null),
                EncounterValidator.SCRIPT_ID_IS_ROLE_ID), "nothing naming a role, nothing to report");
    }

    @Test
    void aBindingWhoseScriptResolvesToARoleIsTheSameFindingFromTheOtherSide() throws IOException {
        EncounterBindingAsset row = EncounterBindingCodecTest.binding("{\"EncounterAsset\": \"Warden\"}", "row", null);
        Map<String, EncounterScriptScan> scripts = Map.of(
                "Boss", script("Boss", true, List.of("zc:engaged", "zc:defeated"), Set.of("Boss"), true, 0));
        List<Finding> asRole = EncounterValidator.validate(scripts, List.of(row), List.of(), null, List.of(),
                id -> id.equals("Warden"));
        Finding finding = find(asRole, EncounterValidator.SCRIPT_ID_IS_ROLE_ID);
        assertEquals("row", finding.sourceId(), "the binding that names the lost script is the source");
        assertFalse(has(asRole, EncounterValidator.BINDING_UNKNOWN_SCRIPT),
                "a script that lost its name to a role is not reported as merely unknown");
        List<Finding> unknown = EncounterValidator.validate(scripts, List.of(row), List.of(), null, List.of(),
                id -> false);
        assertTrue(has(unknown, EncounterValidator.BINDING_UNKNOWN_SCRIPT));
        assertFalse(has(unknown, EncounterValidator.SCRIPT_ID_IS_ROLE_ID));
        assertTrue(has(EncounterValidator.validate(scripts, List.of(row), List.of(), null),
                EncounterValidator.BINDING_UNKNOWN_SCRIPT), "with nothing to ask about roles the old answer stands");
    }
}
