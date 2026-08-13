package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * One case per finding, plus the cases that must produce NONE.
 *
 * <p>A validator that cries wolf is a validator an owner learns to scroll past, so the quiet cases
 * are asserted as deliberately as the loud ones - above all the command-head check, which is skipped
 * entirely rather than guessing when nothing can say which commands exist.
 */
class RewardKindValidatorTest {

    static Map<String, RewardKindAsset.Param> params(Object... pairs) {
        Map<String, RewardKindAsset.Param> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (RewardKindAsset.Param) pairs[i + 1]);
        }
        return out;
    }

    static List<String> codesOf(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    static Finding one(List<Finding> findings, String code) {
        List<Finding> matching = findings.stream().filter(f -> f.code().equals(code)).toList();
        assertEquals(1, matching.size(), "expected exactly one " + code + " in " + codesOf(findings));
        return matching.get(0);
    }

    // ==================== auditing a kind ====================

    @Nested
    class AuditingAKind {

        @Test
        void aWellFormedKindReportsNothing() {
            RewardKindAsset asset = RewardKindAsset.of("Mmo_Xp",
                    params("Skill", RewardKindAsset.Param.of(true, null),
                            "Amount", RewardKindAsset.Param.of(true, null)),
                    "mmoawardxp {player} {Skill} {Amount} ({uuid})");

            assertEquals(List.of(), RewardKindValidator.audit(asset));
        }

        @Test
        void aKindWithNoCommandIsAnError() {
            RewardKindAsset asset = RewardKindAsset.of("Mmo_Xp", params(), null);

            Finding finding = one(RewardKindValidator.audit(asset), RewardKindValidator.NO_COMMAND);
            assertEquals(Severity.ERROR, finding.severity());
            assertEquals("Mmo_Xp", finding.sourceId());
            assertEquals(RewardKindValidator.DOMAIN, finding.domain());
        }

        @Test
        void aPlaceholderNothingDeclaresIsAWarning() {
            RewardKindAsset asset = RewardKindAsset.of("Mmo_Xp",
                    params("Amount", RewardKindAsset.Param.of(true, null)),
                    "mmoawardxp {player} {Ammount}");

            Finding finding = one(RewardKindValidator.audit(asset), RewardKindValidator.UNKNOWN_PARAM);
            assertEquals(Severity.WARNING, finding.severity());
            assertTrue(finding.message().contains("Ammount"));
        }

        @Test
        void theReservedPlaceholdersAreNeverReportedAsUnknown() {
            RewardKindAsset asset = RewardKindAsset.of("Mmo_Xp", params(), "ping {player} {uuid}");

            assertFalse(codesOf(RewardKindValidator.audit(asset))
                    .contains(RewardKindValidator.UNKNOWN_PARAM));
        }

        @Test
        void aDeclaredParameterTheCommandNeverWritesIsAnInfo() {
            RewardKindAsset asset = RewardKindAsset.of("Mmo_Xp",
                    params("Amount", RewardKindAsset.Param.of(true, null),
                            "Flavour", RewardKindAsset.Param.of(null, null)),
                    "mmoawardxp {player} {Amount}");

            Finding finding = one(RewardKindValidator.audit(asset), RewardKindValidator.UNUSED_PARAM);
            assertEquals(Severity.INFO, finding.severity());
            assertTrue(finding.message().contains("Flavour"));
        }

        @Test
        void requiringAParameterThatAlsoHasADefaultIsAnInfo() {
            RewardKindAsset asset = RewardKindAsset.of("Mmo_Xp",
                    params("Amount", RewardKindAsset.Param.of(true, "1")),
                    "mmoawardxp {player} {Amount}");

            Finding finding = one(RewardKindValidator.audit(asset),
                    RewardKindValidator.REQUIRED_WITH_DEFAULT);
            assertEquals(Severity.INFO, finding.severity());
        }
    }

    // ==================== the command head ====================

    @Nested
    class TheCommandHead {

        @Test
        void aHeadNoServerAnswersToIsAWarningThatAdmitsItsBlindSpot() {
            RewardKindAsset asset = RewardKindAsset.of("Mmo_Xp", params(), "mmoawardxp {player}");

            Finding finding = one(RewardKindValidator.audit(asset, Set.of("give", "summon")),
                    RewardKindValidator.UNKNOWN_COMMAND);
            assertEquals(Severity.WARNING, finding.severity());
            assertTrue(finding.message().contains("alias"),
                    "an alias is not in the engine's list, so the finding must say the check can be wrong");
        }

        @Test
        void aRegisteredHeadIsSilentWhicheverWayItIsSpelled() {
            assertEquals(List.of(), RewardKindValidator.audit(
                    RewardKindAsset.of("A", params(), "/Give {player} Bread"), Set.of("give")));
        }

        @Test
        void nothingKnowingWhichCommandsExistSkipsTheCheckRatherThanGuessing() {
            RewardKindAsset asset = RewardKindAsset.of("Mmo_Xp", params(), "mmoawardxp {player}");

            assertEquals(List.of(), RewardKindValidator.audit(asset, null));
            assertEquals(List.of(), RewardKindValidator.audit(asset, Set.of()));
        }

        @Test
        void thereIsNoCommandRegistryInAUnitJvmAndThatIsNotAFailure() {
            assertNull(RewardKindValidator.registeredCommandHeads(),
                    "the probe has to degrade to 'nobody can say', never throw into a content audit");
        }
    }

    // ==================== auditing a reward written for a kind ====================

    @Nested
    class AuditingAReward {

        static final RewardKindAsset KIND = RewardKindAsset.of("Mmo_Xp",
                params("Skill", RewardKindAsset.Param.of(true, null),
                        "Silent", RewardKindAsset.Param.of(false, "false")),
                "mmoawardxp {player} {Skill} --silent={Silent}");

        @Test
        void aRewardAnsweringEveryRequiredParameterReportsNothing() {
            assertEquals(List.of(), RewardKindValidator.auditSpec(KIND,
                    RewardSpec.of("Mmo_Xp", "Skill", "MINING"), "quest:mine_ore"));
        }

        @Test
        void aMissingRequiredParameterIsAnError() {
            Finding finding = one(RewardKindValidator.auditSpec(KIND, RewardSpec.of("Mmo_Xp"),
                    "quest:mine_ore"), RewardKindValidator.MISSING_REQUIRED_PARAM);

            assertEquals(Severity.ERROR, finding.severity());
            assertEquals("quest:mine_ore", finding.sourceId(),
                    "the finding points at the content that has to change, not at the kind");
            assertTrue(finding.message().contains("Skill"));
        }

        @Test
        void aParameterTheKindNeverDeclaredIsAWarning() {
            Finding finding = one(RewardKindValidator.auditSpec(KIND,
                            RewardSpec.of("Mmo_Xp", Map.of("Skill", "MINING", "Ammount", "5")),
                            "quest:mine_ore"),
                    RewardKindValidator.UNKNOWN_PARAM);

            assertEquals(Severity.WARNING, finding.severity());
            assertTrue(finding.message().contains("ammount"));
        }

        @Test
        void aRewardForAKindNobodyAuthoredIsNotThisValidatorsBusiness() {
            assertEquals(List.of(), RewardKindValidator.auditSpec(null,
                    RewardSpec.of("item", "Item", "Coin_Gold"), "quest:mine_ore"));
        }
    }

    // ==================== the vocabulary ====================

    @Test
    void everyCodeIsListedOnceAndCarriesTheDomainPrefix() {
        List<String> codes = RewardKindValidator.codes();

        assertEquals(codes.size(), Set.copyOf(codes).size(), "a duplicated code is a copy-paste slip");
        for (String code : codes) {
            assertTrue(code.startsWith("REWARDKIND_"), code + " should be greppable as this domain's");
        }
    }
}
