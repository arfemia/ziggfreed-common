package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two clicks before anything charges, and the window that decides whether the second one counts.
 *
 * <p>Clock-injected, so the window is assertable without waiting for one.
 */
class ConfirmArmTest {

    @Test
    @DisplayName("the first press arms and the second inside the window goes through")
    void twoPressesGoThrough() {
        ConfirmArm arm = new ConfirmArm(1_000L);

        assertFalse(arm.confirm("reroll:daily", 0L), "the first press only arms");
        assertTrue(arm.isArmed("reroll:daily", 500L));
        assertTrue(arm.confirm("reroll:daily", 500L));
    }

    @Test
    @DisplayName("a confirmed press disarms, so a stray third click charges nothing")
    void aConfirmedPressDisarms() {
        ConfirmArm arm = new ConfirmArm(1_000L);

        arm.confirm("reroll:daily", 0L);
        assertTrue(arm.confirm("reroll:daily", 100L));
        assertFalse(arm.isArmed("reroll:daily", 150L));
        assertFalse(arm.confirm("reroll:daily", 150L), "the next press starts over");
    }

    @Test
    @DisplayName("a press after the window re-arms rather than going through")
    void aLatePressReArms() {
        ConfirmArm arm = new ConfirmArm(1_000L);

        arm.confirm("reroll:daily", 0L);
        assertFalse(arm.isArmed("reroll:daily", 2_000L));
        assertFalse(arm.confirm("reroll:daily", 2_000L));
        assertTrue(arm.confirm("reroll:daily", 2_100L));
    }

    @Test
    @DisplayName("arms are per key, so arming one row never confirms another")
    void armsAreKeyed() {
        ConfirmArm arm = new ConfirmArm(1_000L);

        arm.confirm("reroll:a", 0L);
        assertFalse(arm.confirm("reroll:b", 100L));
        assertTrue(arm.confirm("reroll:a", 200L));
    }

    @Test
    @DisplayName("changing what the panel shows forgets every arm")
    void resetForgetsEverything() {
        ConfirmArm arm = new ConfirmArm(1_000L);

        arm.confirm("reroll:a", 0L);
        arm.reset();
        assertFalse(arm.isArmed("reroll:a", 100L));
        assertFalse(arm.confirm("reroll:a", 100L),
                "an arm left standing behind an unrelated action is a charge waiting to happen");
    }
}
