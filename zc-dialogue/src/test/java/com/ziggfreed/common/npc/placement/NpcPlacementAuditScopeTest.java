package com.ziggfreed.common.npc.placement;

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
 * WHICH findings a placement audit may report, and WHEN.
 *
 * <p>A file-local finding is answerable from the placement file alone, so it holds however much of
 * the server is up. A cross-asset finding asks another store, an open registry or the running
 * universe whether something exists, and the honest answer to that during a fold is "not yet" -
 * asking anyway reports an id that is about to arrive. So the fold-time audit must carry the first
 * kind and none of the second, and the full audit must still carry both once everything is in.
 */
class NpcPlacementAuditScopeTest {

    /**
     * Every code that needs something outside the placement file to answer. A destination's own
     * per-type check belongs to the same half - it asks a registry which mod owns the type - but its
     * codes are the registering mod's to name, so they cannot be listed here.
     */
    private static final List<String> CROSS_ASSET_CODES = List.of(
            "MATCHES_NO_LOADED_WORLD",
            "UNREGISTERED_FACTOR",
            "UNREGISTERED_ANCHOR_PROVIDER",
            "UNKNOWN_DIALOGUE",
            "NO_DISPLAY_NAME");

    @BeforeEach
    @AfterEach
    void reset() {
        PlacementFactorRegistry.clearForTests();
        AnchorResolverRegistry.clearForTests();
        NpcPlacementConfig.getInstance().clearLateAuditForTests();
        NpcPlacementConfig.getInstance().mergePackLayer(Map.of());
    }

    /**
     * One placement carrying an authoring mistake of every registry-backed cross-asset kind at once,
     * plus one purely file-local mistake (an anchor with no usable coordinates) so the file-local
     * half is never trivially empty.
     *
     * <p>{@code MATCHES_NO_LOADED_WORLD} is deliberately NOT exercised through a fixture: it is the
     * one cross-asset check that needs a running universe, and with none the world roster reads
     * "cannot tell" and the check correctly reports nothing.
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
    void theFoldTimeAuditReportsNoCrossAssetFinding() {
        List<String> found = codes(NpcPlacementValidator.auditFileLocal(placementNamingEverythingUnknown()));

        for (String code : CROSS_ASSET_CODES) {
            assertFalse(found.contains(code), () -> "the fold-time audit answered '" + code
                    + "', which needs a store, a registry or a universe that may not be up yet");
        }
    }

    @Test
    void theFoldTimeAuditStillReportsWhatTheFileSaysAboutItself() {
        List<String> found = codes(NpcPlacementValidator.auditFileLocal(placementNamingEverythingUnknown()));

        assertTrue(found.contains("INCOMPLETE_COORDS"),
                "a shape mistake is answerable from the file alone, so a fold must still report it");
    }

    @Test
    void theFullAuditStillReportsTheCrossAssetFindings() {
        List<String> found = codes(NpcPlacementValidator.audit(placementNamingEverythingUnknown()));

        assertTrue(found.contains("UNREGISTERED_FACTOR"));
        assertTrue(found.contains("UNREGISTERED_ANCHOR_PROVIDER"));
        assertTrue(found.contains("INCOMPLETE_COORDS"),
                "the full audit is both halves, so the file-local findings must survive the split");
    }

    /**
     * The boot regression this split exists for: mid-fold, the mod owning an id has not run its
     * {@code setup()} yet, so the registry can answer and answers wrongly. The fold-time audit must
     * stay silent about it whatever the registry currently holds.
     */
    @Test
    void aFactorNobodyHasRegisteredYetIsNeverAFoldTimeFinding() {
        NpcPlacementAsset placement = placementNamingEverythingUnknown();

        assertFalse(codes(NpcPlacementValidator.auditFileLocal(placement)).contains("UNREGISTERED_FACTOR"),
                "a factor whose owner registers milliseconds later is not an authoring mistake");
        assertTrue(codes(NpcPlacementValidator.audit(placement)).contains("UNREGISTERED_FACTOR"),
                "once every mod's setup has run, the same id really is unregistered and must be reported");
    }

    /**
     * The two checks whose SOURCE can be absent rather than merely empty: the conversations fold in
     * from a load event, and role builders live in the NPC plugin's registry. With neither up, both
     * must say nothing - a null answer read as an answer would name every placement on the server.
     */
    @Test
    void aCheckWhoseSourceIsNotUpYetReportsNothingRatherThanGuessing() {
        List<String> found = codes(NpcPlacementValidator.audit(placementNamingEverythingUnknown()));

        assertFalse(found.contains("UNKNOWN_DIALOGUE"),
                "no conversation has loaded, so 'no file carries it' is not something we know");
        assertFalse(found.contains("NO_DISPLAY_NAME"),
                "no role registry is up, so 'this role carries no name' is not something we know");
    }

    // ==================== the config's two moments ====================

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
