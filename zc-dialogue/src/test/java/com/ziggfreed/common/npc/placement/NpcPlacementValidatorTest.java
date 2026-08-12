package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code Identity.Appearance}: naming both a {@code Model} and a {@code Base} leaves it ambiguous
 * which look was meant, and an override authored with no {@code Base} to apply it to does nothing
 * at all. Every one of them is invisible at runtime; this pins them as findings.
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

    // ==================== appearance ====================

    private static NpcPlacementAsset placementWithAppearance(AppearanceSpec appearance) {
        return NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of(null, "zc_base", appearance, null, null),
                null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null, null);
    }

    private static boolean has(List<Finding> issues, String code) {
        return issues.stream().anyMatch(i -> code.equals(i.code()));
    }

    @Test
    void authoringBothModelAndBaseIsAnError() {
        List<Finding> issues = NpcPlacementValidator.audit(placementWithAppearance(
                AppearanceSpec.of("Human_Male_01", "Human_Female_01", null, null, null, null, null, null)));

        assertTrue(has(issues, "APPEARANCE_MODEL_AND_BASE"),
                "the two forms are the group's one exclusive choice, so both is never what the author meant");
    }

    @Test
    void anOverrideWithNoBaseToApplyItToIsAWarning() {
        List<Finding> issues = NpcPlacementValidator.audit(placementWithAppearance(
                AppearanceSpec.of("Human_Male_01", null, "some/texture.png", null, null, 2.0, null, null)));

        assertTrue(has(issues, "APPEARANCE_OVERRIDE_WITHOUT_BASE"),
                "a Texture beside Model changes nothing at all, which is impossible to see in game");
        assertFalse(has(issues, "APPEARANCE_MODEL_AND_BASE"));
    }

    @Test
    void anOverrideWithNoFormAtAllIsFlaggedTheSameWay() {
        List<Finding> issues = NpcPlacementValidator.audit(placementWithAppearance(
                AppearanceSpec.of(null, null, null, null, null, 2.0, null, null)));

        assertTrue(has(issues, "APPEARANCE_OVERRIDE_WITHOUT_BASE"));
    }

    @Test
    void eitherFormOnItsOwnIsClean() {
        assertFalse(has(NpcPlacementValidator.audit(placementWithAppearance(
                AppearanceSpec.model("Human_Male_01"))), "APPEARANCE_MODEL_AND_BASE"));

        List<Finding> clone = NpcPlacementValidator.audit(placementWithAppearance(
                AppearanceSpec.of(null, "Human_Male_01", "some/texture.png", null, null, 2.0, null, null)));
        assertFalse(has(clone, "APPEARANCE_MODEL_AND_BASE"));
        assertFalse(has(clone, "APPEARANCE_OVERRIDE_WITHOUT_BASE"));
    }

    @Test
    void equipmentIsNotACloneOverrideAndNeedsNoBase() {
        List<Finding> issues = NpcPlacementValidator.audit(placementWithAppearance(
                AppearanceSpec.of("Human_Male_01", null, null, null, null, null, null,
                        AppearanceSpec.Equipment.of(null, new String[] { "Weapon_Sword_Iron" }, null, null))));

        assertFalse(has(issues, "APPEARANCE_OVERRIDE_WITHOUT_BASE"),
                "equipment rides the role, not the model, so it is meaningful beside either form");
    }

    // ==================== the template a generated role varies ====================

    @Test
    void anAppearanceWithNoTemplateNamedIsAnError() {
        NpcPlacementAsset noTemplate = NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of(null, null, AppearanceSpec.model("Human_Male_01"), null, null),
                null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null, null);

        assertTrue(has(NpcPlacementValidator.audit(noTemplate), "NO_BASE_ROLE"),
                "a variant with nothing to be a variant OF is a role the engine cannot build at all");
    }

    @Test
    void theTemplateChecksStaySilentWhenThereIsNoEngineToAsk() {
        List<Finding> issues = NpcPlacementValidator.audit(placementWithAppearance(
                AppearanceSpec.model("Human_Male_01")));

        assertFalse(has(issues, "UNKNOWN_TEMPLATE"),
                "outside a running server there are no loaded roles, so 'no such template' would be a guess "
                        + "rather than an answer");
        assertFalse(has(issues, "MODIFY_KEY_NOT_PARAMETERIZED"),
                "the parameter set can only be read off a loaded template, so this check reports nothing here");
    }

    @Test
    void noInteractOrNoBindingsProducesNoInteractFindings() {
        NpcPlacementAsset noInteract = NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of("some_role", null, null, null, null), null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null, null);

        List<Finding> issues = NpcPlacementValidator.audit(noInteract);

        assertFalse(issues.stream().anyMatch(i -> i.code().startsWith("BINDING_KEY") || i.code().startsWith("UNCLAIMED_BINDING")));
    }
}
