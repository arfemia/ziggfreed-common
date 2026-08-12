package com.ziggfreed.common.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * Every mistake an identity overlay can make is silent at runtime, which is the only reason this
 * validator exists. A file that selects nothing simply never applies; two files claiming one role
 * both look right on their own; and two roles spelled with different capitals are ONE role to the
 * engine, so an author can compare two files and see no problem.
 */
class NpcIdentityValidatorTest {

    private static boolean has(List<Finding> findings, String code) {
        return findings.stream().anyMatch(f -> f.code().equals(code));
    }

    private static Finding find(List<Finding> findings, String code) {
        return findings.stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void aFileThatSelectsNothingIsReportedRatherThanIgnored() {
        List<Finding> findings = NpcIdentityValidator.audit(
                NpcIdentityAsset.of("orphan", null, null, "some_id", null));
        assertTrue(has(findings, NpcIdentityValidator.NO_SELECTOR));
        assertEquals(Severity.ERROR, find(findings, NpcIdentityValidator.NO_SELECTOR).severity());
    }

    @Test
    void aFileThatNamesNoIdIsReportedRatherThanIgnored() {
        List<Finding> findings = NpcIdentityValidator.audit(
                NpcIdentityAsset.of("silent", "Kweebec_Elder", null, null, null));
        assertTrue(has(findings, NpcIdentityValidator.NO_NPC_ID));
    }

    @Test
    void aCompleteFileReportsNothing() {
        assertTrue(NpcIdentityValidator.audit(
                NpcIdentityAsset.of("elder", "Kweebec_Elder", null, "village_elder", null)).isEmpty());
    }

    @Test
    void authoringBothSelectorsSaysWhichOneApplies() {
        List<Finding> findings = NpcIdentityValidator.audit(
                NpcIdentityAsset.of("both", "Kweebec_Elder", "Kweebecs", "village_elder", null));
        assertTrue(has(findings, NpcIdentityValidator.ROLE_AND_GROUP));
        assertTrue(find(findings, NpcIdentityValidator.ROLE_AND_GROUP).message().contains("Kweebec_Elder"),
                "the message must name what actually wins");
    }

    @Test
    void anAliasRepeatingThePrimaryIsARemarkNotAProblem() {
        List<Finding> findings = NpcIdentityValidator.audit(NpcIdentityAsset.of("elder", "Kweebec_Elder",
                null, "village_elder", new String[] {"Village_Elder"}));
        assertTrue(has(findings, NpcIdentityValidator.REDUNDANT_ALIAS));
        assertFalse(find(findings, NpcIdentityValidator.REDUNDANT_ALIAS).isProblem());
    }

    @Test
    void twoFilesClaimingOneRoleNameTheFileThatWins() {
        List<Finding> findings = NpcIdentityValidator.audit(List.of(
                NpcIdentityAsset.of("zzz_late", "Kweebec_Elder", null, "late", null),
                NpcIdentityAsset.of("aaa_early", "Kweebec_Elder", null, "early", null)));
        Finding duplicate = find(findings, NpcIdentityValidator.DUPLICATE_ROLE);
        assertEquals("zzz_late", duplicate.sourceId(), "the LOSER is what needs fixing");
        assertTrue(duplicate.message().contains("aaa_early"), "and it must say who beat it");
    }

    @Test
    void twoFilesNamingRolesThatDifferOnlyByCaseAreTheSameCollision() {
        List<Finding> findings = NpcIdentityValidator.audit(List.of(
                NpcIdentityAsset.of("aaa", "Kweebec_Elder", null, "one", null),
                NpcIdentityAsset.of("bbb", "kweebec_elder", null, "two", null)));
        assertTrue(has(findings, NpcIdentityValidator.CASE_ONLY_ROLE_COLLISION),
                "the engine folds these to one role, so an author staring at both sees nothing wrong");
    }

    @Test
    void twoFilesClaimingOneGroupAreReportedToo() {
        List<Finding> findings = NpcIdentityValidator.audit(List.of(
                NpcIdentityAsset.of("aaa", null, "Kweebecs", "one", null),
                NpcIdentityAsset.of("bbb", null, "Kweebecs", "two", null)));
        assertTrue(has(findings, NpcIdentityValidator.DUPLICATE_GROUP));
    }

    @Test
    void everyFindingCarriesTheIdentityDomain() {
        List<Finding> findings = NpcIdentityValidator.audit(
                NpcIdentityAsset.of("broken", null, null, null, null));
        assertFalse(findings.isEmpty());
        findings.forEach(f -> assertEquals(NpcIdentityValidator.DOMAIN, f.domain()));
    }
}
