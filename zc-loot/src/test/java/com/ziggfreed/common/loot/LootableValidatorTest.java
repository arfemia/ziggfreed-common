package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * What the validator catches, one case per finding. The bar for adding one is that the mistake
 * produces SILENCE at runtime - content that quietly does nothing is the kind nobody reports as a
 * bug until much later.
 */
class LootableValidatorTest {

    static List<Finding> audit(Roll roll) {
        return LootableValidator.auditRoll(roll, "fixture", null);
    }

    static boolean has(List<Finding> findings, String code) {
        return findings.stream().anyMatch(f -> f.code().equals(code));
    }

    static Finding find(List<Finding> findings, String code) {
        return findings.stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void everyFindingCarriesTheDomainSoAnAggregateCanGroupThem() {
        List<Finding> findings = audit(Roll.of(null, null, null, null, null, null));
        assertTrue(findings.size() > 0);
        findings.forEach(f -> assertEquals(LootableValidator.DOMAIN, f.domain()));
    }

    @Test
    void aRollThatGrantsNothingAndPlaysNothingIsReported() {
        assertTrue(has(audit(Roll.of(null, null, null, null, null, null)),
                LootableValidator.NO_ROLL_CONTENT));
    }

    @Test
    void anImpossibleChanceIsAnErrorBecauseTheRollCanNeverFire() {
        List<Finding> findings = audit(Roll.of(null, null, FactorFormula.of(0.0, null, null), null,
                LootGrants.ofItem("Coin", 1), null));
        assertEquals(Severity.ERROR, find(findings, LootableValidator.IMPOSSIBLE_CHANCE).severity());
    }

    @Test
    void aChanceWithFactorsIsNotJudgedImpossibleOnItsBaseAlone() {
        FactorFormula chance = FactorFormula.of(0.0,
                new FactorFormula.Term[] {FactorFormula.Term.of("mymod:luck", null, 5.0)}, null);
        assertTrue(!has(audit(Roll.of(null, null, chance, null, LootGrants.ofItem("Coin", 1), null)),
                LootableValidator.IMPOSSIBLE_CHANCE),
                "a base of 0 is the normal shape for a purely factor-driven chance");
    }

    @Test
    void anAlwaysCertainChanceIsOnlyAnInfoBecauseItStillWorks() {
        List<Finding> findings = audit(Roll.of(null, null, FactorFormula.of(100.0, null, null), null,
                LootGrants.ofItem("Coin", 1), null));
        assertEquals(Severity.INFO, find(findings, LootableValidator.CERTAIN_CHANCE).severity());
    }

    @Test
    void anInvertedClampIsAnError() {
        FactorFormula chance = FactorFormula.of(10.0, null, FactorFormula.Clamp.of(90.0, 10.0));
        assertTrue(has(audit(Roll.of(null, null, chance, null, LootGrants.ofItem("Coin", 1), null)),
                LootableValidator.INVERTED_CLAMP));
    }

    @Test
    void aConditionAskingForMoreThanItAllowsIsAnError() {
        FactorCondition impossible = FactorCondition.of("mymod:quality", null, 9.0, 2.0);
        List<Finding> findings = audit(Roll.of(null, new FactorCondition[] {impossible}, null, null,
                LootGrants.ofItem("Coin", 1), null));
        assertEquals(Severity.ERROR, find(findings, LootableValidator.INVERTED_BOUNDS).severity());
    }

    @Test
    void aConditionWithNoFactorIdIsReportedRatherThanSilentlySkipped() {
        List<Finding> findings = audit(Roll.of(null,
                new FactorCondition[] {FactorCondition.of(null, null, 1.0, null)}, null, null,
                LootGrants.ofItem("Coin", 1), null));
        assertTrue(has(findings, LootableValidator.BLANK_CONDITION));
    }

    @Test
    void aFloorAboveZeroOnALadderThatSumsNothingIsUnreachable() {
        Roll.Ladder ladder = Roll.Ladder.of(null, new Roll.Ladder.Floor[] {
                Roll.Ladder.Floor.of(50.0, LootGrants.ofItem("Silver", 1), null)});
        List<Finding> findings = audit(Roll.of(null, null, null, ladder, null, null));
        assertTrue(has(findings, LootableValidator.UNREACHABLE_FLOOR));
        assertTrue(has(findings, LootableValidator.LADDER_NO_FACTORS));
    }

    @Test
    void twoFloorsSharingAThresholdAreReportedSoTheAuthorIsNotSurprised() {
        Roll.Ladder ladder = Roll.Ladder.of(
                new FactorFormula.Term[] {FactorFormula.Term.of("mymod:luck", null, null)},
                new Roll.Ladder.Floor[] {
                        Roll.Ladder.Floor.of(50.0, LootGrants.ofItem("First", 1), null),
                        Roll.Ladder.Floor.of(50.0, LootGrants.ofItem("Last", 1), null)});
        assertTrue(has(audit(Roll.of(null, null, null, ladder, null, null)),
                LootableValidator.DUPLICATE_FLOOR));
    }

    @Test
    void aLadderWithNoFloorsCanNeverPayAnythingOut() {
        Roll.Ladder ladder = Roll.Ladder.of(
                new FactorFormula.Term[] {FactorFormula.Term.of("mymod:luck", null, null)}, null);
        assertTrue(has(audit(Roll.of(null, null, null, ladder, LootGrants.ofItem("Coin", 1), null)),
                LootableValidator.LADDER_NO_FLOORS));
    }

    @Test
    void aFloorThatGrantsAndPlaysNothingIsReported() {
        Roll.Ladder ladder = Roll.Ladder.of(
                new FactorFormula.Term[] {FactorFormula.Term.of("mymod:luck", null, null)},
                new Roll.Ladder.Floor[] {Roll.Ladder.Floor.of(10.0, null, null)});
        assertTrue(has(audit(Roll.of(null, null, null, ladder, LootGrants.ofItem("Coin", 1), null)),
                LootableValidator.EMPTY_FLOOR));
    }

    @Test
    void blankGrantEntriesAreEachReported() {
        LootGrants grants = LootGrants.of(
                new LootGrants.Item[] {LootGrants.Item.of(null, 1), LootGrants.Item.of("Coin", 0)},
                new String[] {" "}, new String[] {""},
                new LootGrants.Reward[] {LootGrants.Reward.of(null, Map.of())});
        List<Finding> findings = LootableValidator.auditRoll(
                Roll.of(null, null, null, null, grants, null), "fixture", null);

        assertTrue(has(findings, LootableValidator.BLANK_ITEM));
        assertTrue(has(findings, LootableValidator.NON_POSITIVE_COUNT));
        assertTrue(has(findings, LootableValidator.BLANK_DROP_LIST));
        assertTrue(has(findings, LootableValidator.BLANK_COMMAND));
        assertTrue(has(findings, LootableValidator.BLANK_REWARD_KIND));
    }

    @Test
    void anUnregisteredRewardKindIsOnlyAWarningBecauseTheModMayJustBeAbsent() {
        LootGrants grants = LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of("absentmod:mana", Map.of())});
        List<Finding> findings = LootableValidator.auditRoll(
                Roll.of(null, null, null, null, grants, null), "fixture", new RewardKindRegistry("t"));

        assertEquals(Severity.WARNING, find(findings, LootableValidator.UNKNOWN_REWARD_KIND).severity());
    }

    @Test
    void aRegisteredRewardKindPassesQuietly() {
        RewardKindRegistry kinds = new RewardKindRegistry("t");
        kinds.register("currency", "t", (spec, subject) -> { });
        LootGrants grants = LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of("Currency", Map.of())});
        assertTrue(!has(LootableValidator.auditRoll(Roll.of(null, null, null, null, grants, null),
                "fixture", kinds), LootableValidator.UNKNOWN_REWARD_KIND),
                "kind ids are matched without regard to case");
    }

    @Test
    void aReferenceToATableNothingShipsIsAWarningNotAnError() {
        List<Finding> findings = LootableValidator.auditRef(
                LootRef.of(new String[] {"nothing_ships_this", " "}, null), "site", null);
        assertEquals(Severity.WARNING, find(findings, LootableValidator.UNKNOWN_TABLE).severity());
        assertTrue(has(findings, LootableValidator.BLANK_TABLE_REF));
    }

    @Test
    void anEmptyRefIsNotAFinding() {
        assertTrue(LootableValidator.auditRef(LootRef.of(null, null), "site", null).isEmpty());
        assertTrue(LootableValidator.auditRef(null, "site", null).isEmpty());
    }

    @Test
    void everyDeclaredCodeIsDistinct() {
        List<String> codes = LootableValidator.codes();
        assertEquals(codes.size(), codes.stream().distinct().count());
    }
}
