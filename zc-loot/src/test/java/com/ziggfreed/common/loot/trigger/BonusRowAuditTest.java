package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.LootableValidator;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.reward.MomentItems;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/** The mistakes a bonus row makes in silence, named before a player asks where their find went. */
class BonusRowAuditTest {

    private RewardKindRegistry kinds;

    @BeforeEach
    void kinds() {
        kinds = new RewardKindRegistry("bonus-row-audit-test");
        MomentItems.registerInto(kinds);
    }

    @AfterEach
    void reset() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of());
    }

    private static LootRef commanding() {
        return LootRef.of(null, new Roll[] {Roll.of(null, null, null, null,
                LootGrants.of(null, null, new String[] {"say found"}, null), null)});
    }

    private static LootRef momentItem() {
        return LootRef.of(null, new Roll[] {Roll.of(null, null, null, null, LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of(MomentItems.KIND, null)}), null)});
    }

    private List<Finding> audit(Map<String, BonusRowAsset> rows) {
        BonusRowConfig.getInstance().mergePackLayer(rows);
        return BonusRowAudit.auditAll(kinds, new FactorRegistry("bonus-row-audit-test"));
    }

    private static boolean has(List<Finding> findings, String code, String sourceId) {
        return findings.stream().anyMatch(f -> code.equals(f.code()) && sourceId.equals(f.sourceId()));
    }

    private static boolean warnsPassOnly(List<Finding> findings, String rowId) {
        return findings.stream().anyMatch(f -> LootableValidator.PASS_ONLY_REWARD_KIND.equals(f.code())
                && f.sourceId().startsWith(rowId));
    }

    @Test
    void aRowNamingNoMomentIsAnErrorStampedWithTheTablesDomain() {
        List<Finding> findings = audit(Map.of("fixture_nomoment", BonusRowAsset.of(null, "*", null, commanding(), null)));

        Finding finding = findings.stream().filter(f -> BonusRowAudit.NO_MOMENT.equals(f.code())).findFirst()
                .orElseThrow(() -> new AssertionError(findings.toString()));
        assertEquals("fixture_nomoment", finding.sourceId());
        assertEquals(Severity.ERROR, finding.severity());
        assertEquals(BonusRowAudit.DOMAIN, finding.domain());
    }

    @Test
    void aWildcardInTheMiddleIsWarned() {
        assertTrue(has(audit(Map.of("fixture_middle",
                BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Ore_*_Cracked", null, commanding(), null))),
                BonusRowAudit.BAD_PATTERN, "fixture_middle"));
    }

    @Test
    void aChanceThatCanNeverRiseOffZeroIsAnError() {
        assertTrue(has(audit(Map.of("fixture_never",
                BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_*", FactorFormula.of(0.0, null, null), commanding(), null))),
                BonusRowAudit.IMPOSSIBLE_CHANCE, "fixture_never"));
    }

    @Test
    void aFactorNothingAnswersIsWarnedAndANullVocabularySkipsTheCheck() {
        FactorFormula chance = FactorFormula.of(5.0,
                new FactorFormula.Term[] {FactorFormula.Term.of("nosuchmod:whatever", null, 1.0)}, null);

        assertTrue(has(audit(Map.of("fixture_factor",
                BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_*", chance, commanding(), null))),
                BonusRowAudit.UNKNOWN_FACTOR, "fixture_factor"));
        assertFalse(has(BonusRowAudit.auditAll(kinds, null), BonusRowAudit.UNKNOWN_FACTOR, "fixture_factor"));
    }

    @Test
    void aRowWithNoLootIsANoteNotAProblem() {
        List<Finding> findings = audit(Map.of("fixture_hole",
                BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_Geode*", null, LootRef.of(null, null), null)));

        assertTrue(has(findings, BonusRowAudit.NO_LOOT, "fixture_hole"));
        assertTrue(findings.stream().filter(f -> BonusRowAudit.NO_LOOT.equals(f.code()))
                .allMatch(f -> f.severity() == Severity.INFO));
    }

    @Test
    void twoRowsPinningDownOneNameAreNoted() {
        assertTrue(has(audit(Map.of(
                "fixture_a", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Ore_Test", null, commanding(), null),
                "fixture_b", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "*Ore_Test*", null, commanding(), null))),
                BonusRowAudit.OVERLAPPING_PATTERN, "fixture_b"));
    }

    @Test
    void aMomentItemPaysOnlyInAPickupRow() {
        List<Finding> findings = audit(Map.of(
                "fixture_break", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "*Test_Name*", null, momentItem(), null),
                "fixture_kill", BonusRowAsset.of(BonusMoment.KILL_MOB, "*Test_Name*", null, momentItem(), null),
                "fixture_pickup", BonusRowAsset.of(BonusMoment.PICKUP_ITEM, "*Test_Name*", null, momentItem(), null)));

        assertTrue(warnsPassOnly(findings, "fixture_break"), findings::toString);
        assertTrue(warnsPassOnly(findings, "fixture_kill"), findings::toString);
        assertFalse(warnsPassOnly(findings, "fixture_pickup"), findings::toString);
    }

    @Test
    void aConsumersCodesAndLootFoldStampItsOwnFindings() {
        BonusRowAudit.Codes codes = new BonusRowAudit.Codes("fixture_domain", "F_NO_MOMENT", "F_BAD_PATTERN",
                "F_IMPOSSIBLE", "F_UNKNOWN", "F_NO_LOOT", "F_OVERLAP");
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_nomoment", BonusRowAsset.of(null, "*", null, commanding(), null),
                "fixture_missing", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_*", null,
                        LootRef.of(new String[] {"fixture_no_such_table"}, null), null)));
        BonusRowConfig config = BonusRowConfig.getInstance();

        List<Finding> findings = BonusRowAudit.audit(config.table(), config.momentlessRows(), codes, kinds, null,
                loot -> loot.stream().map(f -> new Finding(f.severity(), f.code(), f.message(), f.sourceId(),
                        "fixture_domain")).toList());

        assertTrue(has(findings, "F_NO_MOMENT", "fixture_nomoment"), findings::toString);
        assertTrue(has(findings, LootableValidator.UNKNOWN_TABLE, "fixture_missing"), findings::toString);
        assertTrue(findings.stream().allMatch(f -> "fixture_domain".equals(f.domain())), findings::toString);
    }
}
