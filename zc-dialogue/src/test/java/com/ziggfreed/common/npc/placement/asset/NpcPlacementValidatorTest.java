package com.ziggfreed.common.npc.placement.asset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.NpcDestinations;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The authoring mistakes that are otherwise SILENT.
 *
 * <p>In {@code Interact}: describing one press-F twice, which leaves half the file saying something
 * that never runs. In {@code Identity}: naming no {@code Role}, which leaves nothing to spawn. Both
 * are invisible at runtime - an NPC that never appears reads exactly like one nobody has walked to
 * yet - so they are pinned here as findings.
 */
class NpcPlacementValidatorTest {

    private static NpcPlacementAsset placementWith(NpcPlacementAsset.Interact interact) {
        return NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of("some_role"),
                null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null,
                interact);
    }

    private static boolean has(List<Finding> issues, String code) {
        return issues.stream().anyMatch(i -> code.equals(i.code()));
    }

    // ==================== interact ====================

    @Test
    void authoringBothInteractFormsIsAnError() {
        List<Finding> issues = NpcPlacementValidator.audit(placementWith(
                NpcPlacementAsset.Interact.of("hub_intro", NpcDestinations.Quests.of(null))));

        assertTrue(has(issues, "INTERACT_BOTH_FORMS"),
                "one press-F described twice leaves half the file saying something that never runs");
        assertTrue(issues.stream()
                        .filter(i -> "INTERACT_BOTH_FORMS".equals(i.code()))
                        .allMatch(i -> i.severity() == Severity.ERROR),
                "and it is an error rather than a precedence rule");
    }

    @Test
    void eitherFormOnItsOwnIsClean() {
        assertFalse(has(NpcPlacementValidator.audit(
                        placementWith(NpcPlacementAsset.Interact.of("hub_intro"))),
                "INTERACT_BOTH_FORMS"));
        assertFalse(has(NpcPlacementValidator.audit(
                        placementWith(NpcPlacementAsset.Interact.of(null, NpcDestinations.Quests.of(null)))),
                "INTERACT_BOTH_FORMS"));
    }

    @Test
    void noInteractAtAllProducesNoInteractFindings() {
        List<Finding> issues = NpcPlacementValidator.audit(placementWith(null));

        assertFalse(has(issues, "INTERACT_BOTH_FORMS"),
                "a placement that opens nothing of its own is not an authoring mistake");
    }

    // ==================== identity ====================

    /** A placement whose Identity says who it is but never says WHAT to stand there. */
    @Test
    void anIdentityNamingNoRoleIsAnError() {
        NpcPlacementAsset noRole = NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of(null, "some_character", null),
                null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null, null);

        assertTrue(has(NpcPlacementValidator.audit(noRole), "NO_ROLE"),
                "a placement with no role names nothing the engine can spawn, and says so nowhere at runtime");
    }

    @Test
    void anIdentityNamingARoleIsClean() {
        assertFalse(has(NpcPlacementValidator.audit(placementWith(null)), "NO_ROLE"));
    }
}
