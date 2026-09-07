package com.ziggfreed.common.world;

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
 * <p>The live half (resolving the world's gameplay config and the environment covering a block) is
 * an engine read, so it lands in-game rather than here, as with every other world-touching helper.
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
}
