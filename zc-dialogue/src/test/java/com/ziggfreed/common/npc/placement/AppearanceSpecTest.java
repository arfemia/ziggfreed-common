package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@link AppearanceSpec}'s decode contract, both forms of it.
 *
 * <p>The group is a set of independent knobs behind ONE exclusive choice ({@code Model} to use a
 * model as it is, {@code Base} to clone and re-dress one), and every leaf is {@code appendInherited}
 * so "the same NPC, but bigger" stays a two-line child file. Both properties are proved here rather
 * than assumed, because a lost leaf under inheritance shows up in game as an NPC that silently
 * reverts to its base look.
 */
class AppearanceSpecTest {

    private static NpcPlacementAsset decode(String json, String id, String parentId, NpcPlacementAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(NpcPlacementAsset.class, id, parentId);
        return NpcPlacementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    private static NpcPlacementAsset decodeRoot(String json, String id) throws IOException {
        return decode(json, id, null, null);
    }

    private static AppearanceSpec appearanceOf(NpcPlacementAsset asset) {
        return asset.getIdentity().getAppearance();
    }

    // ==================== round trip ====================

    @Test
    void everyKnobOfTheCloneFormDecodes() throws Exception {
        AppearanceSpec spec = appearanceOf(decodeRoot("""
                { "Identity": { "BaseRole": "zc_base", "Appearance": {
                    "Base": "Human_Male_01",
                    "Texture": "NPC/Human/Textures/Villager.png",
                    "GradientSet": "Hair",
                    "GradientId": "Black",
                    "Scale": 1.25,
                    "Particles": [ { "SystemId": "Spectre_Void_Hands", "TargetNodeName": "Chest",
                                     "Color": "#88ff88", "Scale": 0.5,
                                     "PositionOffset": { "Y": 0.4 },
                                     "RotationOffset": { "Yaw": 90.0 },
                                     "DetachedFromModel": true } ],
                    "Equipment": { "Armor": ["Armor_Iron_Chest"], "Hotbar": ["Weapon_Sword_Iron"],
                                   "OffHand": ["Weapon_Shield_Iron"], "DefaultOffHandSlot": 0 } } } }
                """, "dressed"));

        assertEquals("Human_Male_01", spec.getBase());
        assertNull(spec.getModel(), "the two forms are exclusive, and only one was authored");
        assertEquals("NPC/Human/Textures/Villager.png", spec.getTexture());
        assertEquals("Hair", spec.getGradientSet());
        assertEquals("Black", spec.getGradientId());
        assertEquals(1.25, spec.getScale());

        AppearanceSpec.ParticleSpec particle = spec.getParticles()[0];
        assertEquals("Spectre_Void_Hands", particle.getSystemId());
        assertEquals("Chest", particle.getTargetNodeName());
        assertEquals("#88ff88", particle.getColor());
        assertEquals(0.5, particle.getScale());
        assertEquals(0.4, particle.getPositionOffset().effectiveY());
        assertEquals(0.0, particle.getPositionOffset().effectiveX(), "an unauthored axis stays at 0");
        assertEquals(90.0, particle.getRotationOffset().getYaw());
        assertNull(particle.getRotationOffset().getPitch());
        assertTrue(particle.getDetachedFromModel());

        AppearanceSpec.Equipment equipment = spec.getEquipment();
        assertEquals(List.of("Armor_Iron_Chest"), List.of(equipment.getArmor()));
        assertEquals(List.of("Weapon_Sword_Iron"), List.of(equipment.getHotbar()));
        assertEquals(List.of("Weapon_Shield_Iron"), List.of(equipment.getOffHand()));
        assertEquals(0, equipment.getDefaultOffHandSlot());
    }

    @Test
    void theModelFormIsJustOneKey() throws Exception {
        AppearanceSpec spec = appearanceOf(decodeRoot("""
                { "Identity": { "BaseRole": "zc_base", "Appearance": { "Model": "Human_Male_01" } } }
                """, "plain"));

        assertTrue(spec.hasModel());
        assertFalse(spec.hasBase());
        assertFalse(spec.hasCloneOverrides());
        assertFalse(spec.isBlank());
    }

    @Test
    void anEmptyGroupIsBlankAndDoesNotOptIntoRoleGeneration() throws Exception {
        NpcPlacementAsset asset = decodeRoot("""
                { "Identity": { "BaseRole": "zc_base", "Appearance": { } } }
                """, "empty");

        assertTrue(appearanceOf(asset).isBlank());
        assertFalse(asset.getIdentity().usesGeneratedRole(),
                "an appearance group carrying nothing describes no NPC, so it must not trigger generation");
    }

    @Test
    void anAppearanceCarryingOnlyEquipmentStillOptsIntoRoleGeneration() throws Exception {
        NpcPlacementAsset asset = decodeRoot("""
                { "Identity": { "BaseRole": "zc_base",
                                "Appearance": { "Equipment": { "Hotbar": ["Weapon_Sword_Iron"] } } } }
                """, "armed");

        assertFalse(appearanceOf(asset).isBlank());
        assertTrue(asset.getIdentity().usesGeneratedRole(),
                "arming the base role's own look is a real variant and needs a role of its own");
        assertFalse(asset.getIdentity().usesGeneratedModel(),
                "but with no Base to clone there is no model to generate");
    }

    // ==================== Parent inheritance, leaf by leaf ====================

    @Test
    void aChildOverridingOneAppearanceLeafKeepsItsSiblings() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Identity": { "BaseRole": "zc_base", "Appearance": {
                    "Base": "Human_Male_01", "Texture": "a.png", "Scale": 1.0,
                    "Particles": [ { "SystemId": "Spectre_Void_Hands", "TargetNodeName": "Chest" } ] } } }
                """, "base_npc");

        AppearanceSpec child = appearanceOf(decode("""
                { "Identity": { "Appearance": { "Scale": 2.0 } } }
                """, "big_npc", "base_npc", parent));

        assertEquals(2.0, child.getScale(), "the child's own leaf wins");
        assertEquals("Human_Male_01", child.getBase(), "the form itself must survive a partial override");
        assertEquals("a.png", child.getTexture());
        assertEquals(1, child.getParticles().length,
                "an untouched list leaf is inherited whole - this is what appendInherited buys per leaf");
    }

    @Test
    void particlesIsOneLeafSoAuthoringItReplacesTheWholeList() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Identity": { "BaseRole": "zc_base", "Appearance": { "Base": "Human_Male_01",
                    "Particles": [ { "SystemId": "A", "TargetNodeName": "Chest" },
                                   { "SystemId": "B", "TargetNodeName": "L-Arm" } ] } } }
                """, "two_particles");

        AppearanceSpec child = appearanceOf(decode("""
                { "Identity": { "Appearance": { "Particles": [ { "SystemId": "C" } ] } } }
                """, "one_particle", "two_particles", parent));

        assertEquals(1, child.getParticles().length);
        assertEquals("C", child.getParticles()[0].getSystemId());
    }

    @Test
    void aChildSwitchingToTheOtherFormInheritsTheOneItDidNotAuthor() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Identity": { "BaseRole": "zc_base", "Appearance": { "Base": "Human_Male_01" } } }
                """, "clone_form");

        AppearanceSpec child = appearanceOf(decode("""
                { "Identity": { "Appearance": { "Model": "Human_Female_01" } } }
                """, "model_form", "clone_form", parent));

        assertTrue(child.hasBothForms(),
                "inheritance never removes a leaf, so a child naming Model beside an inherited Base is the "
                        + "contradiction the validator reports rather than a silent switch");
        assertNotNull(child.getBase());
    }
}
