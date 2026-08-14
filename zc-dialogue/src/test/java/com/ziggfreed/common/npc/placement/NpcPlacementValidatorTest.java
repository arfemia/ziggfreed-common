package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.validation.Finding;

/**
 * The authoring mistakes that are otherwise SILENT.
 *
 * <p>In {@code Interact.Bindings}: a channel key with no {@code namespace:} prefix has no owner and
 * simply vanishes, and a namespace nobody claimed is ignored at every press-F. In
 * {@code Identity}: naming no {@code Role} leaves nothing to spawn. Every one of them is invisible
 * at runtime - an NPC that never appears reads exactly like one nobody has walked to yet - so this
 * pins them as findings.
 */
class NpcPlacementValidatorTest {

    @BeforeEach
    @AfterEach
    void reset() {
        NpcPlacementBindings.clearForTests();
    }

    private static NpcPlacementAsset placementWithBindings(Map<String, PlacementBinding> bindings) {
        return NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of("some_role"),
                null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null,
                NpcPlacementAsset.Interact.of(null, bindings));
    }

    @Test
    void aColonlessBindingKeyIsFlagged() {
        List<Finding> issues = NpcPlacementValidator.audit(
                placementWithBindings(Map.of("no_namespace", PlacementBinding.value("x"))));

        assertTrue(issues.stream().anyMatch(i -> "BINDING_KEY_NO_NAMESPACE".equals(i.code())),
                "a key with no 'namespace:channel' prefix silently drops at runtime and must be flagged");
    }

    @Test
    void aBindingKeyStartingWithAColonHasNoNamespaceEither() {
        List<Finding> issues = NpcPlacementValidator.audit(
                placementWithBindings(Map.of(":ui_target", PlacementBinding.value("x"))));

        assertTrue(issues.stream().anyMatch(i -> "BINDING_KEY_NO_NAMESPACE".equals(i.code())));
    }

    @Test
    void aProperlyNamespacedKeyIsNotFlaggedAsColonless() {
        NpcPlacementBindings.register("yourmod", ctx -> { });
        List<Finding> issues = NpcPlacementValidator.audit(
                placementWithBindings(Map.of("yourmod:ui_target", PlacementBinding.value("x"))));

        assertFalse(issues.stream().anyMatch(i -> "BINDING_KEY_NO_NAMESPACE".equals(i.code())));
    }

    @Test
    void anUnclaimedNamespaceIsFlagged() {
        List<Finding> issues = NpcPlacementValidator.audit(
                placementWithBindings(Map.of("yourmod:ui_target", PlacementBinding.value("x"))));

        assertTrue(issues.stream().anyMatch(i -> "UNCLAIMED_BINDING_NAMESPACE".equals(i.code())),
                "a namespace nobody registered a handler for is silently ignored at press-F");
    }

    @Test
    void aClaimedNamespaceIsNotFlaggedAsUnclaimed() {
        NpcPlacementBindings.register("yourmod", ctx -> { });

        List<Finding> issues = NpcPlacementValidator.audit(
                placementWithBindings(Map.of("yourmod:ui_target", PlacementBinding.value("x"))));

        assertFalse(issues.stream().anyMatch(i -> "UNCLAIMED_BINDING_NAMESPACE".equals(i.code())));
    }

    // ==================== identity ====================

    private static boolean has(List<Finding> issues, String code) {
        return issues.stream().anyMatch(i -> code.equals(i.code()));
    }

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
        assertFalse(has(NpcPlacementValidator.audit(placementWithBindings(Map.of())), "NO_ROLE"));
    }

    @Test
    void noInteractOrNoBindingsProducesNoInteractFindings() {
        NpcPlacementAsset noInteract = NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of("some_role"), null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null, null);

        List<Finding> issues = NpcPlacementValidator.audit(noInteract);

        assertFalse(issues.stream().anyMatch(i -> i.code().startsWith("BINDING_KEY") || i.code().startsWith("UNCLAIMED_BINDING")));
    }
}
