package com.ziggfreed.common.loot;

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
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardKindAsset;
import com.ziggfreed.common.loot.reward.RewardKindConfig;
import com.ziggfreed.common.loot.reward.RewardKindValidator;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.trigger.BonusMoment;
import com.ziggfreed.common.loot.trigger.BonusRowAsset;
import com.ziggfreed.common.loot.trigger.BonusRowAudit;
import com.ziggfreed.common.loot.trigger.BonusRowConfig;
import com.ziggfreed.common.validation.Finding;

/**
 * The on-demand audit reads every loaded table as authored and checks its rewards against the
 * vocabulary this server actually pays, so a kind registered by any mod passes and a kind nobody
 * registered is named. The same reply then audits every loaded reward-kind file.
 */
class LootAuditTest {

    private static final String KNOWN = "Loot_Audit_Known_Kind";
    private static final String UNKNOWN = "Loot_Audit_Unknown_Kind";

    private FactorRegistry installedBefore;

    @BeforeEach
    void registerOneKind() {
        RewardKinds.shared().register(KNOWN, (spec, subject) -> { });
        installedBefore = LootRewardKinds.installedFactors();
    }

    @AfterEach
    void reset() {
        RewardKinds.clear();
        LootableConfig.getInstance().mergePackLayer(Map.of());
        RewardKindConfig.getInstance().mergePackLayer(Map.of());
        BonusRowConfig.getInstance().mergePackLayer(Map.of());
        LootRewardKinds.factors(installedBefore);
    }

    private static Roll paying(String kind) {
        return Roll.of(null, null, null, null,
                LootGrants.of(null, null, null, new LootGrants.Reward[] {LootGrants.Reward.of(kind, null)}), null);
    }

    @Test
    void aKindTheServerPaysPassesAndOneItDoesNotIsNamed() {
        LootableConfig.getInstance().mergePackLayer(Map.of(
                "audited", LootableAsset.of("audited", new Roll[] {paying(KNOWN), paying(UNKNOWN)})));

        List<Finding> findings = LootAudit.auditAll();

        List<Finding> unknown = findings.stream()
                .filter(f -> f.code().equals(LootableValidator.UNKNOWN_REWARD_KIND)).toList();
        assertEquals(1, unknown.size(), findings.toString());
        assertTrue(unknown.get(0).message().contains(UNKNOWN), unknown.toString());
        assertEquals("audited roll 1", unknown.get(0).sourceId());
    }

    @Test
    void anEmptyTableIsReported() {
        LootableConfig.getInstance().mergePackLayer(Map.of("hollow", LootableAsset.of("hollow", null)));

        assertTrue(LootAudit.auditAll().stream().anyMatch(f -> f.code().equals(LootableValidator.EMPTY_TABLE)
                && f.sourceId().equals("hollow")));
    }

    @Test
    void aRewardKindFileFindingJoinsTheTableFindingsInOneAudit() {
        // A command-backed kind file that authors no command: RewardKindValidator flags REWARDKIND_NO_COMMAND.
        RewardKindConfig.getInstance().mergePackLayer(Map.of("Test_Audit_Kind",
                RewardKindAsset.of("Test_Audit_Kind", null, null)));
        LootableConfig.getInstance().mergePackLayer(Map.of("audited", LootableAsset.of("audited", new Roll[0])));

        List<Finding> findings = LootAudit.auditAll();

        assertTrue(findings.stream().anyMatch(f -> RewardKindValidator.DOMAIN.equals(f.domain())
                && RewardKindValidator.NO_COMMAND.equals(f.code())), "the reward-kind file is audited");
        assertTrue(findings.stream().anyMatch(f -> !RewardKindValidator.DOMAIN.equals(f.domain())),
                "the table audit still runs");
    }

    @Test
    void aFileThatOnlyDecoratesAJavaKindIsNotReported() {
        // A command-less file over a kind a Java handler answers only gives that kind its Presentation,
        // the shape the library's own Lootable, Droplist and Effect files take. The validator notes it;
        // the audit leaves the note out, so a stock server can still answer "nothing to report".
        RewardKindConfig.getInstance().mergePackLayer(Map.of(KNOWN, RewardKindAsset.of(KNOWN, null, null)));
        assertEquals(List.of(RewardKindValidator.PRESENTATION_ONLY),
                RewardKindValidator.auditAll().stream().map(Finding::code).toList(),
                "the validator itself still notes the decoration");

        List<Finding> kindFindings = LootAudit.auditAll().stream()
                .filter(f -> RewardKindValidator.DOMAIN.equals(f.domain())).toList();

        assertEquals(List.of(), kindFindings, "a decoration is not a finding");
    }

    private static LootRef saying() {
        return LootRef.of(null, new Roll[] {Roll.of(null, null, null, null,
                LootGrants.of(null, null, new String[] {"say found"}, null), null)});
    }

    private static FactorFormula reading(String factorId) {
        return FactorFormula.of(1.0, new FactorFormula.Term[] {FactorFormula.Term.of(factorId, null, 1.0)}, null);
    }

    @Test
    void aBonusRowFindingJoinsTheSameAudit() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_nomoment", BonusRowAsset.of(null, "*", null, saying(), null)));

        List<Finding> findings = LootAudit.auditAll();

        assertTrue(findings.stream().anyMatch(f -> BonusRowAudit.NO_MOMENT.equals(f.code())
                && "fixture_nomoment".equals(f.sourceId())), findings::toString);
    }

    @Test
    void aBonusRowIsAuditedAgainstTheVocabularyTheRollingKindsRead() {
        FactorRegistry loot = new FactorRegistry("loot-audit-test");
        loot.register("fixture:known", "fixture", ctx -> 1.0);
        LootRewardKinds.factors(loot);
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_known", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_*", reading("fixture:known"), saying(), null),
                "fixture_unknown", BonusRowAsset.of(BonusMoment.KILL_MOB, "*", reading("fixture:unknown"), saying(), null)));

        List<Finding> findings = LootAudit.auditAll();

        assertFalse(findings.stream().anyMatch(f -> BonusRowAudit.UNKNOWN_FACTOR.equals(f.code())
                && "fixture_known".equals(f.sourceId())), findings::toString);
        assertTrue(findings.stream().anyMatch(f -> BonusRowAudit.UNKNOWN_FACTOR.equals(f.code())
                && "fixture_unknown".equals(f.sourceId())), findings::toString);
    }
}
