package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.reward.CollectingRewardKind;
import com.ziggfreed.common.loot.reward.MomentItems;
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
    void aKindThatCollectsOntoAPassPassesQuietlyBecauseATableCannotKnowWhereItIsRolled() {
        RewardKindRegistry kinds = new RewardKindRegistry("t");
        kinds.register("Test_Tally", "t", CollectingRewardKind.of("Test_Tally", StringBuilder.class,
                (tally, spec) -> tally.append(spec.kind())));
        LootGrants grants = LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of("Test_Tally", Map.of())});

        List<Finding> findings = LootableValidator.auditRoll(Roll.of(null, null, null, null, grants, null),
                "fixture", kinds);

        assertTrue(findings.isEmpty(), () -> "a pass-scoped kind in a table is not a finding: " + findings);
    }

    // ==================== a site's carried collectors ====================

    /** A registry with one collecting kind (collector StringBuilder), one interface one, one plain. */
    private static RewardKindRegistry collectingKinds() {
        RewardKindRegistry kinds = new RewardKindRegistry("t");
        kinds.register("Test_Tally", "t", CollectingRewardKind.of("Test_Tally", StringBuilder.class,
                (tally, spec) -> tally.append(spec.kind())));
        kinds.register("Test_Chars", "t", CollectingRewardKind.of("Test_Chars", CharSequence.class,
                (chars, spec) -> { }));
        kinds.register("Test_Plain", "t", (spec, subject) -> { });
        return kinds;
    }

    private static LootGrants rewards(String... kindIds) {
        LootGrants.Reward[] out = new LootGrants.Reward[kindIds.length];
        for (int i = 0; i < kindIds.length; i++) {
            out[i] = LootGrants.Reward.of(kindIds[i], Map.of());
        }
        return LootGrants.of(null, null, null, out);
    }

    private static LootRef inline(LootGrants grants) {
        return LootRef.of(null, new Roll[] {Roll.of(null, null, null, null, grants, null)});
    }

    private static long count(List<Finding> findings, String code) {
        return findings.stream().filter(f -> f.code().equals(code)).count();
    }

    @Test
    void theReleasedFormsAndANullCarriedSetStaySilentOnACollectingKind() {
        RewardKindRegistry kinds = collectingKinds();
        LootRef ref = inline(rewards("Test_Tally"));

        assertTrue(LootableValidator.auditRef(ref, "site", kinds).isEmpty());
        assertTrue(LootableValidator.auditRef(ref, "site", kinds, null).isEmpty());
        assertTrue(LootableValidator.auditRoll(ref.getRolls()[0], "site", kinds, null).isEmpty(),
                "null is no site at all, as silent as a table");
    }

    @Test
    void aSiteCarryingTheCollectorIsSilent() {
        List<Finding> findings = LootableValidator.auditRef(inline(rewards("Test_Tally")), "site",
                collectingKinds(), Set.of(StringBuilder.class));
        assertTrue(findings.isEmpty(), () -> "the pass carries it, so it pays: " + findings);
    }

    @Test
    void aCarriedImplementationOfAnInterfaceCollectorSatisfiesIt() {
        List<Finding> findings = LootableValidator.auditRef(inline(rewards("Test_Chars")), "site",
                collectingKinds(), Set.of(StringBuilder.class));
        assertTrue(findings.isEmpty(), () -> "a StringBuilder is a CharSequence, as handleAs reads it: " + findings);
    }

    @Test
    void aSiteWhosePassCarriesNothingWarnsForAnInlineRollAndALadderFloor() {
        RewardKindRegistry kinds = collectingKinds();
        Roll.Ladder ladder = Roll.Ladder.of(
                new FactorFormula.Term[] {FactorFormula.Term.of("mymod:luck", null, null)},
                new Roll.Ladder.Floor[] {Roll.Ladder.Floor.of(0.0, rewards("Test_Tally"), null)});
        LootRef ref = LootRef.of(null, new Roll[] {
                Roll.of(null, null, null, null, rewards("Test_Tally"), null),
                Roll.of(null, null, null, ladder, null, null)});

        List<Finding> findings = LootableValidator.auditRef(ref, "site", kinds, Set.of());

        assertEquals(2, count(findings, LootableValidator.PASS_ONLY_REWARD_KIND), () -> findings.toString());
        Finding roll = findings.stream().filter(f -> f.code().equals(LootableValidator.PASS_ONLY_REWARD_KIND))
                .findFirst().orElseThrow();
        assertEquals(Severity.WARNING, roll.severity());
        assertEquals(LootableValidator.DOMAIN, roll.domain());
        assertEquals("site roll 0", roll.sourceId());
        assertTrue(findings.stream().anyMatch(f -> f.code().equals(LootableValidator.PASS_ONLY_REWARD_KIND)
                && f.sourceId().equals("site roll 1 floor 0")), () -> "the floor's reward too: " + findings);
        assertEquals(1, count(LootableValidator.auditRoll(ref.getRolls()[0], "site", kinds, Set.of()),
                LootableValidator.PASS_ONLY_REWARD_KIND), "auditRoll takes the same set");
    }

    @Test
    void aKindThatPaysAnywhereIsNeverFlaggedForWhatAPassCarries() {
        List<Finding> findings = LootableValidator.auditRef(inline(rewards("Test_Plain")), "site",
                collectingKinds(), Set.of());
        assertTrue(findings.isEmpty(), () -> "a plain kind is no site's concern: " + findings);
    }

    @Test
    void aMomentItemCountAboveTheCeilingIsAWarningAndOneAtItIsNot() {
        RewardKindRegistry kinds = new RewardKindRegistry("t");
        MomentItems.registerInto(kinds);
        LootGrants over = LootGrants.of(null, null, null, new LootGrants.Reward[] {LootGrants.Reward.of(
                MomentItems.KIND, Map.of("Count", Integer.toString(MomentItems.MAX_COPIES + 1)))});
        LootGrants at = LootGrants.of(null, null, null, new LootGrants.Reward[] {LootGrants.Reward.of(
                "moment_item", Map.of("count", Integer.toString(MomentItems.MAX_COPIES)))});

        List<Finding> findings = LootableValidator.auditRoll(Roll.of(null, null, null, null, over, null), "t", kinds);

        assertEquals(Severity.WARNING, find(findings, LootableValidator.MOMENT_COUNT_ABOVE_CEILING).severity());
        assertTrue(!has(LootableValidator.auditRoll(Roll.of(null, null, null, null, at, null), "t", kinds),
                LootableValidator.MOMENT_COUNT_ABOVE_CEILING), "the ceiling itself pays in full");
    }

    private static LootGrants momentCount(String count) {
        return LootGrants.of(null, null, null, new LootGrants.Reward[] {LootGrants.Reward.of(
                MomentItems.KIND, Map.of("Count", count))});
    }

    @Test
    void aMomentItemCountThatCannotPayIsAWarning() {
        RewardKindRegistry kinds = new RewardKindRegistry("t");
        MomentItems.registerInto(kinds);
        for (String bad : List.of("0", "-1", "lots", "NaN")) {
            List<Finding> findings = LootableValidator.auditRoll(
                    Roll.of(null, null, null, null, momentCount(bad), null), "t", kinds);

            assertEquals(1, count(findings, LootableValidator.MOMENT_COUNT_INVALID),
                    () -> "a Count of " + bad + " fails at payout: " + findings);
            assertEquals(Severity.WARNING, find(findings, LootableValidator.MOMENT_COUNT_INVALID).severity());
            assertTrue(!has(findings, LootableValidator.MOMENT_COUNT_ABOVE_CEILING), bad);
        }
    }

    @Test
    void anUnwrittenOrPositiveMomentItemCountIsFine() {
        RewardKindRegistry kinds = new RewardKindRegistry("t");
        MomentItems.registerInto(kinds);
        LootGrants unwritten = LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of(MomentItems.KIND, Map.of())});

        assertTrue(LootableValidator.auditRoll(Roll.of(null, null, null, null, unwritten, null), "t", kinds)
                .isEmpty(), "an unwritten Count is one copy");
        assertTrue(LootableValidator.auditRoll(Roll.of(null, null, null, null, momentCount("0.5"), null), "t",
                kinds).isEmpty(), "a fraction pays");
    }

    @Test
    void theMomentItemCountChecksRunInATablesPoolTooButTheCarriedCheckDoesNot() {
        RewardKindRegistry kinds = new RewardKindRegistry("t");
        MomentItems.registerInto(kinds);
        LootPool pool = LootPool.of(null, new LootPool.Entry[] {
                LootPool.Entry.of(null, null, momentCount("0")),
                LootPool.Entry.of(null, null, momentCount(Integer.toString(MomentItems.MAX_COPIES + 1))),
                LootPool.Entry.of(null, null, momentCount("1"))});

        List<Finding> findings = LootableValidator.auditPool(pool, "table", kinds);

        assertEquals(1, count(findings, LootableValidator.MOMENT_COUNT_INVALID), () -> findings.toString());
        assertEquals(1, count(findings, LootableValidator.MOMENT_COUNT_ABOVE_CEILING), () -> findings.toString());
        assertEquals(0, count(findings, LootableValidator.PASS_ONLY_REWARD_KIND),
                "a table cannot know what its pass carries");
    }

    @Test
    void theVocabularyNamesTheSiteCodeAndBothCountCodes() {
        assertTrue(LootableValidator.codes().contains(CollectingRewardKind.SITE_CODE));
        assertTrue(LootableValidator.codes().contains(LootableValidator.MOMENT_COUNT_ABOVE_CEILING));
        assertTrue(LootableValidator.codes().contains(LootableValidator.MOMENT_COUNT_INVALID));
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
