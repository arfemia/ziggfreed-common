package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.validation.Finding;

/**
 * The on-demand table audit reads every loaded table as authored and checks its rewards against the
 * vocabulary this server actually pays, so a kind registered by any mod passes and a kind nobody
 * registered is named.
 */
class LootAuditTest {

    private static final String KNOWN = "Loot_Audit_Known_Kind";
    private static final String UNKNOWN = "Loot_Audit_Unknown_Kind";

    @BeforeEach
    void registerOneKind() {
        RewardKinds.shared().register(KNOWN, (spec, subject) -> { });
    }

    @AfterEach
    void reset() {
        RewardKinds.clear();
        LootableConfig.getInstance().mergePackLayer(Map.of());
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
}
