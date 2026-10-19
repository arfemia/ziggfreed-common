package com.ziggfreed.common.usegate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The keep a release leans on (gear a player had when gear levels arrived keeps working for them): the pure rule over
 * a stack's {@code ZigUseGateKeptFor} stamp. A stamp keeps a stack for the player it names in the group it names,
 * and for nobody and nothing else.
 */
class UseGateKeepsTest {

    private static final UUID HOLDER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void aStampedStackPassesForItsPlayerAndNotAnother() {
        Map<String, String[]> stamp = UseGateKeeps.stamped(null, HOLDER, "ZigLevel");

        assertTrue(UseGateKeeps.keeps(stamp, HOLDER, "ZigLevel"));
        assertFalse(UseGateKeeps.keeps(stamp, STRANGER, "ZigLevel"), "a stamp works only for whom it names");
        assertFalse(UseGateKeeps.keeps(null, HOLDER, "ZigLevel"), "no stamp keeps nothing");
        assertFalse(UseGateKeeps.keeps(stamp, null, "ZigLevel"), "an unknown player is kept for nothing");
    }

    @Test
    void aStampKeepsOnlyTheGroupItWasKeptFor() {
        Map<String, String[]> stamp = UseGateKeeps.stamped(null, HOLDER, "Mmo_Gear_Levels");

        assertTrue(UseGateKeeps.keeps(stamp, HOLDER, "mmo_gear_levels"), "groups match without regard to case");
        assertFalse(UseGateKeeps.keeps(stamp, HOLDER, "Owner_Gates"), "another group's gate still asks");
        assertFalse(UseGateKeeps.keeps(stamp, HOLDER, null), "the shared default group is a group of its own");
    }

    @Test
    void stampingAddsAKeeperAndLeavesTheRest() {
        Map<String, String[]> first = UseGateKeeps.stamped(null, HOLDER, "ZigLevel");
        Map<String, String[]> second = UseGateKeeps.stamped(first, STRANGER, "ZigLevel");
        Map<String, String[]> again = UseGateKeeps.stamped(second, HOLDER, "ZigLevel");

        assertTrue(UseGateKeeps.keeps(second, HOLDER, "ZigLevel"));
        assertTrue(UseGateKeeps.keeps(second, STRANGER, "ZigLevel"));
        assertEquals(1, first.get("ziglevel").length, "the given stamp is never changed");
        assertArrayEquals(second.get("ziglevel"), again.get("ziglevel"), "a keeper is named once");
    }
}
