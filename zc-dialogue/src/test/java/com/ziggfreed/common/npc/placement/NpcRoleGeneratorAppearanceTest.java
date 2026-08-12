package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.codec.Vec3;

/**
 * What {@link NpcRoleGenerator} actually WRITES for an authored placement: the native variant role
 * and the model clone it points at.
 *
 * <p>Both are plain JSON handed to the engine's own decoders, so the field names and their shapes
 * are the contract - a renamed key is not a compile error anywhere, it is an NPC that loads with no
 * texture and no explanation, or one the engine refuses outright. These fixtures pin the emitted
 * shape exactly, and the template fixture beside them pins the OTHER half of that contract: every
 * key the generator emits has to be a parameter the template declares, or the variant is refused.
 */
class NpcRoleGeneratorAppearanceTest {

    private static final String TEMPLATE = "Template_Zc_Placement_Example";

    private static JsonObject resource(String name) {
        try (InputStream in = NpcRoleGeneratorAppearanceTest.class.getResourceAsStream("/npc/placement/" + name)) {
            assertNotNull(in, name + " must be on the test classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError("could not read " + name, e);
        }
    }

    private static NpcPlacementAsset.Identity identityWith(AppearanceSpec appearance) {
        return NpcPlacementAsset.Identity.of(null, TEMPLATE, appearance, null, null);
    }

    private static AppearanceSpec.Equipment fullKit() {
        return AppearanceSpec.Equipment.of(
                new String[] { "Armor_Iron_Head", "Armor_Iron_Chest" },
                new String[] { "Weapon_Sword_Iron" },
                new String[] { "Weapon_Shield_Iron" },
                0);
    }

    // ==================== the variant, against a hand-written expectation ====================

    @Test
    void aFullyAuthoredPlacementEmitsExactlyTheExpectedVariant() {
        NpcPlacementAsset.Identity identity = NpcPlacementAsset.Identity.of(null, TEMPLATE,
                AppearanceSpec.of(null, "Human_Male_01", "NPC/Human/Textures/Villager.png", "Hair", "Black",
                        1.25, null, fullKit()),
                "npc.guide.name", "npc.guide.hint");

        JsonObject variant = NpcRoleGenerator.buildVariant(identity, "hub_guide");
        assertTrue(variant.get("$Comment").getAsString().contains("hub_guide"),
                "whoever opens a generated file must be able to find the placement that produced it");
        variant.remove("$Comment");

        assertEquals(resource("Expected_Variant_Hub_Guide.json"), variant,
                "the emitted variant is compared whole against the hand-written expectation: a key that moves, "
                        + "changes name or grows a wrapper object is a role the engine reads differently");
    }

    @Test
    void everyKeyTheGeneratorCanEmitIsOneTheTemplateFixtureDeclares() {
        Set<String> declared = resource("Template_Zc_Placement_Example.json")
                .getAsJsonObject("Parameters").keySet();

        assertTrue(declared.containsAll(NpcRoleGenerator.modifyKeys()),
                "a variant may only override a key the template declared in its own Parameters block, so the "
                        + "generator's key set and a placement-backing template's parameter set are one contract. "
                        + "Declared: " + declared + ", emitted: " + NpcRoleGenerator.modifyKeys());
    }

    // ==================== the variant emission table ====================

    @Test
    void theCloneFormPointsTheVariantAtTheGeneratedModel() {
        JsonObject modify = NpcRoleGenerator.buildModify(
                identityWith(AppearanceSpec.of(null, "Human_Male_01", null, null, null, 2.0, null, null)),
                "hub_guide");

        assertEquals("Zc_Gen_Mdl_hub_guide", modify.get("Appearance").getAsString(),
                "the role and the model are written in one pass, so the id they agree on is the whole link");
    }

    @Test
    void theModelFormPointsTheVariantStraightAtTheAuthoredId() {
        JsonObject modify = NpcRoleGenerator.buildModify(
                identityWith(AppearanceSpec.model("Human_Male_01")), "hub_guide");

        assertEquals("Human_Male_01", modify.get("Appearance").getAsString());
    }

    @Test
    void anEquipmentOnlyAppearanceOverridesNoLookAtAll() {
        JsonObject modify = NpcRoleGenerator.buildModify(
                identityWith(AppearanceSpec.of(null, null, null, null, null, null, null,
                        AppearanceSpec.Equipment.of(null, new String[] { "Weapon_Sword_Iron" }, null, null))),
                "armed");

        assertFalse(modify.has("Appearance"),
                "an absent key is what leaves the template's own look standing; writing one would replace it");
        assertEquals(List.of("Weapon_Sword_Iron"),
                modify.getAsJsonArray("Weapons").asList().stream().map(e -> e.getAsString()).toList());
    }

    @Test
    void equipmentLandsUnderTheParameterNamesAPlacementTemplateDeclares() {
        JsonObject modify = NpcRoleGenerator.buildModify(
                identityWith(AppearanceSpec.of("Human_Male_01", null, null, null, null, null, null, fullKit())),
                "knight");

        assertEquals(List.of("Armor_Iron_Head", "Armor_Iron_Chest"),
                modify.getAsJsonArray("Armor").asList().stream().map(e -> e.getAsString()).toList());
        assertEquals(List.of("Weapon_Sword_Iron"),
                modify.getAsJsonArray("Weapons").asList().stream().map(e -> e.getAsString()).toList(),
                "hotbar items are addressed by the parameter name the vanilla humanoid templates use");
        assertEquals(List.of("Weapon_Shield_Iron"),
                modify.getAsJsonArray("OffHand").asList().stream().map(e -> e.getAsString()).toList());
        assertEquals(0, modify.get("DefaultOffHandSlot").getAsInt());
    }

    @Test
    void anUnauthoredKnobIsAbsentRatherThanEmpty() {
        JsonObject modify = NpcRoleGenerator.buildModify(
                identityWith(AppearanceSpec.model("Human_Male_01")), "plain");

        assertEquals(Set.of("Appearance"), modify.keySet(),
                "a variant carries ONLY what it overrides, so an unauthored knob must not be written at all or "
                        + "it replaces the template's own value with a default nobody asked for");
    }

    @Test
    void aVariantIsThreeKeysAndTheOverrides() {
        JsonObject variant = NpcRoleGenerator.buildVariant(
                identityWith(AppearanceSpec.model("Human_Male_01")), "plain");

        assertEquals(Set.of("$Comment", "Type", "Reference", "Modify"), variant.keySet());
        assertEquals("Variant", variant.get("Type").getAsString());
        assertEquals(TEMPLATE, variant.get("Reference").getAsString());
    }

    @Test
    void theAuthoredKeyListMatchesWhatIsEmitted() {
        NpcPlacementAsset.Identity identity = NpcPlacementAsset.Identity.of(null, TEMPLATE,
                AppearanceSpec.model("Human_Male_01"), "npc.guide.name", null);

        assertEquals(List.of("Appearance", "NameTranslationKey"),
                NpcRoleGenerator.authoredModifyKeys(identity, "hub_guide"),
                "the validator asks about exactly the keys the generator will write, so the two must be derived "
                        + "from one place rather than listed twice");
    }

    // ==================== the model clone ====================

    @Test
    void theCloneCarriesTheBaseAsParentAndOnlyTheAuthoredOverrides() {
        JsonObject model = NpcRoleGenerator.buildModel(AppearanceSpec.of("", "Human_Male_01",
                "NPC/Human/Textures/Villager.png", "Hair", "Black", 1.5,
                new AppearanceSpec.ParticleSpec[] {
                        AppearanceSpec.ParticleSpec.of("Spectre_Void_Hands", "Chest", "#88ff88", 0.5,
                                Vec3.of(null, 0.4, null),
                                AppearanceSpec.Rotation.of(90.0, null, null), true) },
                null), "hub_guide");

        assertEquals(Set.of("$Comment", "Parent", "Texture", "GradientSet", "GradientId",
                        "MinScale", "MaxScale", "Particles"),
                model.keySet(),
                "the Model schema declares no additional properties, and an unauthored knob must not be written "
                        + "at all or it overrides the parent's own value with a default nobody asked for");
        assertEquals("Human_Male_01", model.get("Parent").getAsString());
        assertTrue(model.get("$Comment").getAsString().contains("hub_guide"),
                "whoever opens a generated file must be able to find the placement that produced it");
        assertEquals("NPC/Human/Textures/Villager.png", model.get("Texture").getAsString());
        assertEquals("Hair", model.get("GradientSet").getAsString());
        assertEquals("Black", model.get("GradientId").getAsString());

        assertEquals(1.5, model.get("MinScale").getAsDouble());
        assertEquals(model.get("MinScale").getAsDouble(), model.get("MaxScale").getAsDouble(),
                "an authored Scale must be a CONSTANT, not a range the engine draws a random size from");

        JsonObject particle = model.getAsJsonArray("Particles").get(0).getAsJsonObject();
        assertEquals(Set.of("SystemId", "TargetNodeName", "Color", "Scale", "PositionOffset",
                        "RotationOffset", "DetachedFromModel"),
                particle.keySet());
        assertEquals("Spectre_Void_Hands", particle.get("SystemId").getAsString());
        assertEquals("Chest", particle.get("TargetNodeName").getAsString());
        assertEquals(0.4, particle.getAsJsonObject("PositionOffset").get("Y").getAsDouble());
        assertFalse(particle.getAsJsonObject("PositionOffset").has("X"),
                "an unauthored axis is omitted so the engine's own default stands");
        assertEquals(90.0, particle.getAsJsonObject("RotationOffset").get("Yaw").getAsDouble());
        assertTrue(particle.get("DetachedFromModel").getAsBoolean());
    }

    @Test
    void aBareCloneIsJustTheParent() {
        JsonObject model = NpcRoleGenerator.buildModel(AppearanceSpec.of(null, "Human_Male_01",
                null, null, null, null, null, null), "bare");

        assertEquals(Set.of("$Comment", "Parent"), model.keySet());
    }

    @Test
    void aParticleWithNoSystemIdIsDropped() {
        JsonObject model = NpcRoleGenerator.buildModel(AppearanceSpec.of(null, "Human_Male_01",
                null, null, null, null,
                new AppearanceSpec.ParticleSpec[] {
                        AppearanceSpec.ParticleSpec.on(null, "Chest"),
                        AppearanceSpec.ParticleSpec.on("Spectre_Void_Hands", "Chest") },
                null), "partial");

        assertEquals(1, model.getAsJsonArray("Particles").size(),
                "an entry that could never render must not reach the engine as an empty object");
    }

    @Test
    void noModelIsWrittenWhenTheAppearanceNamesOneToUseAsItIs() {
        assertNull(NpcRoleGenerator.buildModel(AppearanceSpec.model("Human_Male_01"), "plain"));
        assertNull(NpcRoleGenerator.buildModel(null, "none"));
    }

    // ==================== the generated model pairing ====================

    @Test
    void theVariantAndTheModelAgreeOnTheGeneratedId() {
        AppearanceSpec appearance = AppearanceSpec.of(null, "Human_Male_01", null, null, null, 1.25, null, null);

        JsonObject variant = NpcRoleGenerator.buildVariant(identityWith(appearance), "hub_guide");
        JsonObject model = NpcRoleGenerator.buildModel(appearance, "hub_guide");

        assertNotNull(model);
        assertEquals(NpcRoleGenerator.generatedModelName("hub_guide"),
                variant.getAsJsonObject("Modify").get("Appearance").getAsString(),
                "the model is written under a name derived from the placement id and nothing else links the two, "
                        + "so the pairing is the whole mechanism");
    }
}
