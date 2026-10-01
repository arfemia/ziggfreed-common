package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.objects.Reference2FloatOpenHashMap;

/**
 * An eject's roster write, on the same map type the engine's {@code EncounterMembers} keeps (the
 * component itself cannot be stood up in a unit JVM): the ejected member is gone, nobody else is
 * touched, and the answer says whether they were on the roster at all. The map is keyed by
 * reference, the way the roster is, so the keys here are distinct objects rather than equal values.
 */
class EncounterMembershipTest {

    @Test
    void anEjectedMemberLeavesTheRosterAndTheOthersStay() {
        Object ejected = new Object();
        Object stays = new Object();
        Reference2FloatOpenHashMap<Object> roster = new Reference2FloatOpenHashMap<>();
        roster.put(ejected, 1.5F);
        roster.put(stays, 2.0F);

        assertTrue(EncounterMembership.dropMember(roster, ejected), "they were on the roster");
        assertFalse(roster.containsKey(ejected));
        assertTrue(roster.containsKey(stays), "only the one member leaves");
        assertEquals(2.0F, roster.getFloat(stays), "and the other member's stamp is untouched");
        assertEquals(1, roster.size());
    }

    @Test
    void ejectingSomeoneNotOnTheRosterAnswersNoAndChangesNothing() {
        Object member = new Object();
        Reference2FloatOpenHashMap<Object> roster = new Reference2FloatOpenHashMap<>();
        roster.put(member, 2.0F);

        assertFalse(EncounterMembership.dropMember(roster, new Object()));
        assertEquals(1, roster.size());
        assertTrue(roster.containsKey(member));
    }

    @Test
    void ejectingTwiceAnswersYesOnlyTheFirstTime() {
        Object member = new Object();
        Reference2FloatOpenHashMap<Object> roster = new Reference2FloatOpenHashMap<>();
        roster.put(member, 0.0F);

        assertTrue(EncounterMembership.dropMember(roster, member),
                "a member whose stamp has decayed to zero is still on the roster");
        assertFalse(EncounterMembership.dropMember(roster, member));
        assertTrue(roster.isEmpty());
    }
}
