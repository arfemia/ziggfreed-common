package com.ziggfreed.common.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The ally seam's rule: a party answer wins whole (a member is an ally whatever the PvP flag, anyone else is not),
 * a player in no party has every other player as an ally only while PvP is off, nobody is their own ally, and a seam
 * left unfilled reads as no party at all.
 */
class AlliesTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    @AfterEach
    void reset() {
        Allies.resetForTests();
    }

    @Test
    void aPartyAnswerWinsWhole() {
        Set<UUID> party = Set.of(ME, FRIEND);
        Allies.installGroups((player, other) -> party.contains(player) ? party.contains(other) : null);
        assertTrue(Allies.areAllies(ME, FRIEND, true), "a party member is an ally with PvP on");
        assertTrue(Allies.areAllies(ME, FRIEND, false));
        assertFalse(Allies.areAllies(ME, STRANGER, false), "in a party, a stranger is no ally even with PvP off");
        assertTrue(Allies.areAllies(STRANGER, ME, false), "a player in no party has everyone while PvP is off");
        assertFalse(Allies.areAllies(STRANGER, ME, true), "and nobody while it is on");
    }

    @Test
    void nobodyIsTheirOwnAlly_andAnUnfilledSeamReadsAsNoParty() {
        assertFalse(Allies.areAllies(ME, ME, false));
        assertTrue(Allies.areAllies(ME, FRIEND, false));
        assertFalse(Allies.areAllies(ME, FRIEND, true));
    }

    @Test
    void aFailingPartyReadReadsAsNoParty() {
        Allies.installGroups((player, other) -> {
            throw new IllegalStateException("down");
        });
        assertTrue(Allies.areAllies(ME, FRIEND, false));
        assertFalse(Allies.areAllies(ME, FRIEND, true));
    }
}
