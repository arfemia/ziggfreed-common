package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Two things this file pins.
 *
 * <p>First, the "cannot tell" contract of {@link RoleTemplates}. Every question it answers is asked
 * of a running engine's loaded roles, and there is none in a unit JVM. A validator that treated
 * silence as "no such template" would report every placement in the server as broken the moment the
 * audit ran a moment too early, so silence has to stay silence.
 *
 * <p>Second, that the old base-role indirection is GONE rather than merely unused. A placement now
 * names a native template role directly, so a leftover registry that still accepted a raw role body
 * would be a second, quietly divergent way to say the same thing.
 */
class RoleTemplatesTest {

    // ==================== the cannot-tell contract ====================

    @Test
    void existenceIsUnansweredWithNoEngineToAsk() {
        assertNull(RoleTemplates.templateExists("Template_Anything"),
                "null means 'cannot answer', which is what keeps an early audit from inventing findings");
    }

    @Test
    void aBlankNameIsUnansweredRatherThanFalse() {
        assertNull(RoleTemplates.templateExists(null));
        assertNull(RoleTemplates.templateExists("  "));
    }

    @Test
    void noKeyIsCalledUnofferedWithNoEngineToAsk() {
        assertTrue(RoleTemplates.unparameterizedKeys("Template_Anything",
                List.of("Appearance", "NameTranslationKey", "Hint")).isEmpty());
    }

    @Test
    void aBlankTemplateOrAnEmptyKeySetAsksNothing() {
        assertTrue(RoleTemplates.unparameterizedKeys(null, List.of("Appearance")).isEmpty());
        assertTrue(RoleTemplates.unparameterizedKeys("Template_Anything", List.of()).isEmpty());
    }

    // ==================== the retired indirection ====================

    @Test
    void theBaseRoleAssetTypeNoLongerExists() {
        for (String gone : List.of(
                "com.ziggfreed.common.npc.placement.NpcBaseRoleAsset",
                "com.ziggfreed.common.npc.placement.NpcBaseRoleConfig",
                "com.ziggfreed.common.npc.placement.NpcBaseRoleValidator")) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(gone, false, RoleTemplatesTest.class.getClassLoader()),
                    gone + " is retired: a placement names a native template role directly, so a store that "
                            + "delivered a raw role body would be a second way to say the same thing");
        }
    }

    @Test
    void theGeneratorKeepsNoBaseRoleRegistry() {
        List<String> retired = List.of("registerBaseRole", "registerBaseRoleResource",
                "registerBaseRoleFromAsset", "hasBaseRole", "registeredBaseRoles", "currentBaseRoleJson",
                "substitute");

        for (Method method : NpcRoleGenerator.class.getDeclaredMethods()) {
            assertFalse(retired.contains(method.getName()),
                    "NpcRoleGenerator." + method.getName() + " belonged to the base-role clone route, which the "
                            + "native variant emission replaced whole");
        }
    }

    @Test
    void theModifyKeySetIsTheOneTemplateContract() {
        assertEquals(List.of("Appearance", "NameTranslationKey", "Hint", "Armor", "Weapons", "OffHand",
                        "DefaultOffHandSlot"),
                NpcRoleGenerator.modifyKeys(),
                "these names are what a placement-backing template has to declare in its Parameters block, so "
                        + "renaming one here is a break for every template already written against it");
    }
}
