package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.world.WorldSelectorValidator.Issue;

/**
 * The two authoring mistakes that are otherwise SILENT in {@code Interact.Bindings}: a channel
 * key with no {@code namespace:} prefix has no owner and simply vanishes, and a namespace nobody
 * claimed is ignored at every press-F. Both are invisible at runtime; this pins them as findings.
 */
class NpcPlacementValidatorTest {

    @BeforeEach
    @AfterEach
    void reset() {
        NpcPlacementBindings.clearForTests();
    }

    private static NpcPlacementAsset placementWithBindings(Map<String, PlacementBinding> bindings) {
        return NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of("some_role", null, null, null, null),
                null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null,
                NpcPlacementAsset.Interact.of(null, bindings));
    }

    @Test
    void aColonlessBindingKeyIsFlagged() {
        List<Issue> issues = NpcPlacementValidator.validate(
                placementWithBindings(Map.of("no_namespace", PlacementBinding.value("x"))));

        assertTrue(issues.stream().anyMatch(i -> "BINDING_KEY_NO_NAMESPACE".equals(i.code())),
                "a key with no 'namespace:channel' prefix silently drops at runtime and must be flagged");
    }

    @Test
    void aBindingKeyStartingWithAColonHasNoNamespaceEither() {
        List<Issue> issues = NpcPlacementValidator.validate(
                placementWithBindings(Map.of(":ui_target", PlacementBinding.value("x"))));

        assertTrue(issues.stream().anyMatch(i -> "BINDING_KEY_NO_NAMESPACE".equals(i.code())));
    }

    @Test
    void aProperlyNamespacedKeyIsNotFlaggedAsColonless() {
        NpcPlacementBindings.register("yourmod", ctx -> { });
        List<Issue> issues = NpcPlacementValidator.validate(
                placementWithBindings(Map.of("yourmod:ui_target", PlacementBinding.value("x"))));

        assertFalse(issues.stream().anyMatch(i -> "BINDING_KEY_NO_NAMESPACE".equals(i.code())));
    }

    @Test
    void anUnclaimedNamespaceIsFlagged() {
        List<Issue> issues = NpcPlacementValidator.validate(
                placementWithBindings(Map.of("yourmod:ui_target", PlacementBinding.value("x"))));

        assertTrue(issues.stream().anyMatch(i -> "UNCLAIMED_BINDING_NAMESPACE".equals(i.code())),
                "a namespace nobody registered a handler for is silently ignored at press-F");
    }

    @Test
    void aClaimedNamespaceIsNotFlaggedAsUnclaimed() {
        NpcPlacementBindings.register("yourmod", ctx -> { });

        List<Issue> issues = NpcPlacementValidator.validate(
                placementWithBindings(Map.of("yourmod:ui_target", PlacementBinding.value("x"))));

        assertFalse(issues.stream().anyMatch(i -> "UNCLAIMED_BINDING_NAMESPACE".equals(i.code())));
    }

    @Test
    void noInteractOrNoBindingsProducesNoInteractFindings() {
        NpcPlacementAsset noInteract = NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of("some_role", null, null, null, null), null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null, null);

        List<Issue> issues = NpcPlacementValidator.validate(noInteract);

        assertFalse(issues.stream().anyMatch(i -> i.code().startsWith("BINDING_KEY") || i.code().startsWith("UNCLAIMED_BINDING")));
    }
}
