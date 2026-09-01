package com.ziggfreed.common.npc.placement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.world.WorldSelector;

/**
 * The config's two audit moments: the fold-time pass answers only what a placement file says about
 * itself, and the deferred late audit answers everything else exactly once per boot, unless a
 * consumer has claimed it to report the same findings in its own vocabulary.
 */
class NpcPlacementConfigAuditTest {

    @BeforeEach
    @AfterEach
    void reset() {
        NpcPlacementConfig.getInstance().clearLateAuditForTests();
        NpcPlacementConfig.getInstance().mergePackLayer(Map.of());
    }

    /**
     * One placement carrying a registry-backed cross-asset authoring mistake (a factor nothing has
     * registered) so the fold-time and late halves give different answers about the same file.
     */
    private static NpcPlacementAsset placementNamingEverythingUnknown() {
        return NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of("some_role"),
                WorldSelector.of(new String[]{"*Forgotten_Temple*"}, null, null),
                NpcPlacementAsset.Anchor.of(null,
                        NpcPlacementAsset.Anchor.Coords.of(1.0, null, 3.0, null),
                        null, null,
                        NpcPlacementAsset.Anchor.Custom.of("yourmod:dungeon_door", null)),
                NpcPlacementAsset.Requires.of(new FactorCondition[]{
                        FactorCondition.of("yourmod:chapter", null, 1.0, null)}),
                null, null,
                NpcPlacementAsset.Interact.of("some_conversation"));
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    @Test
    void theConfigFoldAuditsFileLocalAndTheLateAuditAuditsEverything() {
        NpcPlacementConfig config = NpcPlacementConfig.getInstance();
        config.mergePackLayer(Map.of("test_placement", placementNamingEverythingUnknown()));

        assertFalse(codes(config.auditFileLocal()).contains("UNREGISTERED_FACTOR"));
        assertTrue(codes(config.audit()).contains("UNREGISTERED_FACTOR"));
    }

    @Test
    void theLateAuditRunsOnce() {
        NpcPlacementConfig config = NpcPlacementConfig.getInstance();
        config.mergePackLayer(Map.of("test_placement", placementNamingEverythingUnknown()));

        List<String> lines = new ArrayList<>();
        config.runLateAudit(lines::add, lines::add);
        assertTrue(lines.stream().anyMatch(line -> line.contains("UNREGISTERED_FACTOR")),
                "the deferred audit is where the cross-asset findings are finally reported");

        lines.clear();
        config.runLateAudit(lines::add, lines::add);
        assertEquals(List.of(), lines, "one audit per boot, however many players connect");
    }

    @Test
    void aClaimedLateAuditStandsDown() {
        NpcPlacementConfig config = NpcPlacementConfig.getInstance();
        config.mergePackLayer(Map.of("test_placement", placementNamingEverythingUnknown()));
        config.claimLateAudit("YourMod");

        List<String> lines = new ArrayList<>();
        config.runLateAudit(lines::add, lines::add);

        assertEquals("YourMod", config.lateAuditOwner());
        assertFalse(lines.stream().anyMatch(line -> line.contains("UNREGISTERED_FACTOR")),
                "a consumer reporting these findings in its own command must not have them reported twice");
        assertTrue(lines.stream().anyMatch(line -> line.contains("YourMod")),
                "standing down silently would read as an audit that never ran");
    }
}
