package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementService;

/**
 * A placement names an NPC role and the role file describes the character. There is no second route.
 *
 * <p>Why that is worth a test rather than a comment: a role file is the only place the engine reads
 * some of a character's fields from at all. A role's worn {@code Armor} and its press-F
 * {@code SetInteractable} Hint go through literal readers, so neither can be supplied from outside
 * the file, and whether any given field CAN be is a property of the reader the engine happens to
 * have wired - not something anything outside the engine can inspect. A mechanism that built roles
 * from somewhere else could therefore only ever cover part of a character, and would fail by
 * producing an NPC that never appears, with nothing on screen to say why.
 *
 * <p>So the shape is pinned here: {@code Identity} carries a role id, a character id and its
 * aliases, and nothing that describes a look, a name or a prompt. Anything readded on that side
 * would be a second, quietly partial way to say what a role file already says completely.
 */
class RoleGenerationRetirementTest {

    @Test
    void theRoleBuildingClassesAreGone() {
        for (String gone : List.of(
                "com.ziggfreed.common.npc.placement.NpcRoleGenerator",
                "com.ziggfreed.common.npc.placement.RoleTemplates",
                "com.ziggfreed.common.npc.placement.AppearanceSpec",
                "com.ziggfreed.common.npc.placement.NpcBaseRoleAsset",
                "com.ziggfreed.common.npc.placement.NpcBaseRoleConfig",
                "com.ziggfreed.common.npc.placement.NpcBaseRoleValidator")) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(gone, false, RoleGenerationRetirementTest.class.getClassLoader()),
                    gone + " belonged to building a role from a placement, which a hand-authored role file "
                            + "replaces whole");
        }
    }

    @Test
    void identityDescribesWhoAndNotWhatTheyLookLike() {
        List<String> retired = List.of("getBaseRole", "getAppearance", "getNameKey", "getHintKey",
                "usesGeneratedRole", "usesGeneratedModel");
        List<String> declared = Arrays.stream(NpcPlacementAsset.Identity.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();

        for (String name : retired) {
            assertTrue(!declared.contains(name),
                    "Identity." + name + " described a character's look, name or prompt, all of which the "
                            + "role file owns");
        }
        assertTrue(declared.contains("getRole"), "a placement still has to say WHICH role to stand there");
        assertTrue(declared.contains("getNpcId"), "and still has to say who content calls that character");
    }

    /** The role a placement spawns is exactly the one it names, with no fallback to invent one. */
    @Test
    void theSpawnedRoleIsTheAuthoredRole() {
        NpcPlacementAsset named = NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of("  Zc_Guide  "), null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null),
                        null, null, null, null),
                null, null, null, null);
        assertEquals("Zc_Guide", NpcPlacementService.roleFor(named),
                "the authored id is used as written, trimmed, never rewritten into another name");

        NpcPlacementAsset unnamed = NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of(null, "some_character", null), null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null),
                        null, null, null, null),
                null, null, null, null);
        assertNotNull(unnamed.getIdentity());
        assertNull(NpcPlacementService.roleFor(unnamed),
                "naming no role leaves nothing to stand up, which the validator reports as NO_ROLE");
    }
}
