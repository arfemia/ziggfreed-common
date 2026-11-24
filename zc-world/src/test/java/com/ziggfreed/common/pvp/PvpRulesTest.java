package com.ziggfreed.common.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@code Plugin.ZigPvp} as a file writes it ({@link PvpRules}): every leaf decodes, an unwritten one reads the shipped
 * default, and the bounty rules ({@link PvpBounty}) read off it: when a bounty stands, what ending it is worth, and a
 * departure mid-fight.
 */
class PvpRulesTest {

    @Test
    void everyLeafDecodesAndAnUnwrittenOneReadsItsDefault() throws IOException {
        PvpRules rules = decode("{ \"Enabled\": false, \"Credit\": { \"AssistSeconds\": 20, "
                + "\"Repeat\": { \"Steps\": [ 1, 0.5, 0 ], \"Window\": \"2h\" } }, "
                + "\"Bounty\": { \"At\": 3, \"PerKill\": 10, \"Announce\": false } }");

        assertFalse(rules.enabled());
        assertEquals(20_000L, rules.credit().assistMillis());
        assertEquals(10_000L, rules.credit().environmentMillis(), "unwritten reads the default");
        assertEquals(2L * 60L * 60L * 1000L, rules.credit().repeat().rollingMs());
        assertEquals(0.5, rules.credit().repeat().multiplier(1), 1e-9);
        assertEquals(3, rules.bounty().at());
        assertFalse(rules.bounty().announce());
        assertEquals(PvpRules.DEFAULT_BOUNTY_MAX_STREAK, rules.bounty().maxStreak());

        PvpRules empty = decode("{ }");
        assertTrue(empty.enabled());
        assertEquals(PvpRules.DEFAULT_REPEAT, empty.credit().repeat());
        assertEquals(PvpRules.DEFAULT_BOUNTY_AT, empty.bounty().at());
    }

    @Test
    void aBountyStandsAtItsStreakAndIsWorthItsShareOfHealthPerKill() throws IOException {
        PvpRules.Bounty bounty = decode("{ \"Bounty\": { \"At\": 5, \"PerKill\": 20, \"MaxStreak\": 10 } }")
                .bounty();

        assertFalse(PvpBounty.stands(4, bounty));
        assertTrue(PvpBounty.stands(5, bounty));
        assertTrue(PvpBounty.raised(5, bounty));
        assertTrue(PvpBounty.grew(6, bounty));
        assertEquals(0.0, PvpBounty.worth(4, 150.0, 0.0, bounty), 1e-9, "no bounty, no worth");
        assertEquals(150.0, PvpBounty.worth(5, 150.0, 0.0, bounty), 1e-9, "a fifth of 150 health, five kills");
        assertEquals(300.0, PvpBounty.worth(12, 150.0, 0.0, bounty), 1e-9, "at most ten kills count");
        assertEquals(375.0, PvpBounty.worth(12, 150.0, 25.0, bounty), 1e-9, "the hunter's bonus raises it");

        PvpRules.Bounty off = decode("{ \"Bounty\": { \"Enabled\": false } }").bounty();
        assertFalse(PvpBounty.stands(50, off), "a world with bounties off carries none");
    }

    @Test
    void leavingSoonAfterAPlayersHitEndsTheStreak() throws IOException {
        PvpRules.Bounty bounty = decode("{ \"Bounty\": { \"LogoutSeconds\": 10 } }").bounty();

        assertTrue(PvpBounty.fled(1_000L, 9_000L, bounty));
        assertFalse(PvpBounty.fled(1_000L, 12_000L, bounty));
        assertFalse(PvpBounty.fled(1_000L, 2_000L, decode("{ \"Bounty\": { \"LogoutSeconds\": 0 } }").bounty()),
                "0 never ends one");
    }

    private static PvpRules decode(String json) throws IOException {
        return PvpRules.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }
}
