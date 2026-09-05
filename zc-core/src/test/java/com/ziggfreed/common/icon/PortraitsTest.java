package com.ziggfreed.common.icon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the role-versus-model distinction {@link Portraits} exists to hold: an installed
 * {@link Portraits.RoleArt} says what a role WEARS and wins, and every way of not answering - none
 * installed, a null, a blank, a throw - falls back to the id as written, which is the right path
 * for a target that names a model outright.
 *
 * <p>The seam is process-wide static, so each case installs its own and the teardown puts the
 * refusing default back. Pure fixtures: no engine, no NPC registry, no asset store.
 */
class PortraitsTest {

    @AfterEach
    void resetSeam() {
        Portraits.roleArt(roleId -> null);
    }

    @Test
    void anUnansweredRoleIsAddressedByItsOwnId() {
        Portraits.roleArt(roleId -> null);
        assertEquals("Icons/ModelsGenerated/Boar.png", Portraits.pathFor("Boar"));
    }

    @Test
    void anInstalledAnswerWinsOverTheId() {
        Portraits.roleArt(roleId -> "Mmo_Hub_Temple".equals(roleId)
                ? "Icons/ModelsGenerated/Kweebec_Rootling.png" : null);
        assertEquals("Icons/ModelsGenerated/Kweebec_Rootling.png",
                Portraits.pathFor("Mmo_Hub_Temple"));
        assertEquals("Icons/ModelsGenerated/Boar.png", Portraits.pathFor("Boar"));
    }

    @Test
    void aBlankAnswerFallsBackRatherThanPaintingNothing() {
        Portraits.roleArt(roleId -> "   ");
        assertEquals("Icons/ModelsGenerated/Boar.png", Portraits.pathFor("Boar"));
    }

    @Test
    void aThrowingAnswerCostsThePictureNotTheRow() {
        Portraits.roleArt(roleId -> {
            throw new IllegalStateException("no NPC registry in this JVM");
        });
        assertEquals("Icons/ModelsGenerated/Boar.png", Portraits.pathFor("Boar"));
    }

    @Test
    void theRoleIsTrimmedBeforeItIsAsked() {
        Portraits.roleArt(roleId -> "Boar".equals(roleId) ? "Icons/Custom/Boar.png" : null);
        assertEquals("Icons/Custom/Boar.png", Portraits.pathFor("  Boar  "));
    }

    @Test
    void aBlankRoleIsNoPictureAtAll() {
        assertNull(Portraits.pathFor(null));
        assertNull(Portraits.pathFor("   "));
        assertNull(Portraits.forRole(null));
    }

    @Test
    void forRoleCarriesTheSamePathAsATexture() {
        Portraits.roleArt(roleId -> "Icons/ModelsGenerated/Kweebec_Rootling.png");
        IconSpec spec = Portraits.forRole("Mmo_Hub_Temple");
        assertNull(spec.itemId());
        assertEquals("Icons/ModelsGenerated/Kweebec_Rootling.png", spec.texturePath());
    }
}
