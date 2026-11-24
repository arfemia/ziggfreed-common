package com.ziggfreed.common.instance.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.hub.HubLayout.Claim;
import com.ziggfreed.common.instance.hub.HubLots.Size;

/**
 * The hub district reading's one load-bearing rule: a district standing reads 1, one that does not reads a DEFINITE 0
 * (never null, or a temple stand-in's {@code Max: 0} gate would shut on exactly the server it is for), and only "cannot
 * tell yet" reads null. Pure, no engine type.
 */
class HubStandingTest {

    @Test
    void aStandingDistrictReadsOneAnAbsentOneADefiniteZero() {
        Set<String> standing = Set.of("yours_hall");

        assertEquals(1.0, HubStanding.reading("Yours_Hall", standing), "matched without regard to case");
        assertEquals(1.0, HubStanding.reading(" yours_hall ", standing), "trimmed");
        assertEquals(0.0, HubStanding.reading("Yours_Market", standing),
                "a district that does not stand, or no file defines, is a definite 0");
        assertNull(HubStanding.reading("Yours_Hall", null), "a store that holds nothing yet cannot tell");
        assertNull(HubStanding.reading("  "), "a blank Param names no district");
    }

    @Test
    void whatTheLayoutLaysOnALotStandsAndWhatItLeavesOffDoesNot() {
        HubLayout.Result layout = HubLayout.assign(HubLots.all(), List.of(
                new Claim("Yours_Hall", "North", Size.LARGE, false, false),
                new Claim("Yours_Rival", "North", Size.LARGE, false, false),
                new Claim("Yours_Scaffold", null, Size.LARGE, true, false)));

        Set<String> standing = HubStanding.standingIn(layout);

        assertTrue(standing.contains("yours_hall") ^ standing.contains("yours_rival"),
                "two districts claiming one lot: exactly one stands, " + standing);
        assertTrue(standing.contains("yours_scaffold"), "a placeholder on the empty lots stands too");
        assertFalse(standing.contains("yours_market"), "nothing claimed stands");
    }
}
