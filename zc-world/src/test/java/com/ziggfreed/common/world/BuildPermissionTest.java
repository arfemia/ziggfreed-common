package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.GameMode;

/**
 * The two build permissions the engine checks AFTER dispatching its place-block event, pinned on the
 * pure decision {@link BuildPermission#allowsPlacement(boolean, GameMode, boolean)} the live read
 * feeds: a world that has building turned off refuses everybody, and a protected environment refuses
 * everybody the engine holds to it, which is every placer that is not in creative.
 *
 * <p>The environment half is pinned on its package-private seam, an environment source and a
 * modification rule: a refused environment refuses, and anything that cannot be read allows, so a
 * placement the engine refuses pays nothing while ordinary building keeps its credit. The live section
 * read (the cell's chunk section and its {@code EnvironmentSection}) is an engine read with no chunk
 * store in a unit JVM, so it lands in-game (the port smoke's protected-environment line).
 */
class BuildPermissionTest {

    @Test
    void anOrdinaryWorldAllowsPlacement() {
        assertTrue(BuildPermission.allowsPlacement(true, GameMode.Adventure, true));
        assertTrue(BuildPermission.allowsPlacement(true, GameMode.Creative, true));
        assertTrue(BuildPermission.allowsPlacement(true, null, true),
                "a placer whose game mode could not be read is an ordinary placer");
    }

    @Test
    void aWorldWithBuildingOffRefusesEverybody() {
        assertFalse(BuildPermission.allowsPlacement(false, GameMode.Adventure, true));
        assertFalse(BuildPermission.allowsPlacement(false, GameMode.Creative, true),
                "the world permission is read before the placer's mode, exactly as the engine reads it");
        assertFalse(BuildPermission.allowsPlacement(false, null, true));
    }

    @Test
    void aProtectedEnvironmentRefusesEverybodyOutsideCreative() {
        assertFalse(BuildPermission.allowsPlacement(true, GameMode.Adventure, false));
        assertFalse(BuildPermission.allowsPlacement(true, null, false),
                "an unreadable game mode is held to the environment, the way the engine holds it");
        assertTrue(BuildPermission.allowsPlacement(true, GameMode.Creative, false),
                "the engine only asks the environment about a non-creative placer");
    }

    // ==================== the environment half, on its seam ====================

    /** Environment 7 is protected; every other id lets blocks be modified. */
    private static final BuildPermission.ModificationRule SEVEN_IS_PROTECTED = id -> id != 7;

    @Test
    void aRefusedEnvironmentRefusesAndAnAllowedOneAllows() {
        assertFalse(BuildPermission.environmentAllowsModification((x, y, z) -> 7, SEVEN_IS_PROTECTED, 10, 64, -3),
                "the cell's environment forbids building, so the placement pays nothing");
        assertTrue(BuildPermission.environmentAllowsModification((x, y, z) -> 3, SEVEN_IS_PROTECTED, 10, 64, -3));
    }

    @Test
    void anUnreadableSectionAllows() {
        assertTrue(BuildPermission.environmentAllowsModification((x, y, z) -> null, id -> false, 10, 64, -3),
                "no section in memory, or no environment data on it: cannot tell, so the engine decides");
    }

    @Test
    void aReadThatThrowsAllows() {
        assertTrue(BuildPermission.environmentAllowsModification((x, y, z) -> {
            throw new IllegalStateException("the section went away");
        }, id -> false, 0, 64, 0));
        assertTrue(BuildPermission.environmentAllowsModification((x, y, z) -> {
            throw new NoSuchMethodError("EnvironmentSection.get");
        }, id -> false, 0, 64, 0), "a read this server build cannot link is a read that cannot tell");
    }

    @Test
    void aRuleThatThrowsAllows() {
        assertTrue(BuildPermission.environmentAllowsModification((x, y, z) -> 7, id -> {
            throw new IllegalStateException("no environment store");
        }, 0, 64, 0));
    }

    @Test
    void theCellIsAskedAtItsWorldCoordinates() {
        int[] asked = new int[3];
        BuildPermission.environmentAllowsModification((x, y, z) -> {
            asked[0] = x;
            asked[1] = y;
            asked[2] = z;
            return 1;
        }, id -> true, -207, 122, 47);

        assertArrayEquals(new int[] {-207, 122, 47}, asked, "the section masks world coordinates itself");
    }

    @Test
    void noWorldAllows() {
        assertTrue(BuildPermission.environmentAllowsModification(null, 0, 64, 0));
        assertTrue(BuildPermission.allowsPlacement(null, GameMode.Adventure, 0, 64, 0));
    }
}
