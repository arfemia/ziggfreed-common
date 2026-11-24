package com.ziggfreed.common.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.diminish.DiminishLedger;
import com.ziggfreed.common.loot.diminish.DiminishSpec;

/**
 * The PvP ledger's credit rules ({@link PvpCredit}, over a {@link PvpTally}): who a death credits and with what, what
 * credits nothing, and the repeat guard counting each fallen player apart. Pure: every engine read is handed in.
 */
class PvpCreditTest {

    private static final UUID VICTIM = new UUID(0L, 1L);
    private static final UUID KILLER = new UUID(0L, 2L);
    private static final UUID HELPER = new UUID(0L, 3L);
    private static final UUID PARTY_MATE = new UUID(0L, 4L);
    private static final UUID OTHER_VICTIM = new UUID(0L, 5L);

    private static final long NOW = 1_000_000L;
    private static final long ASSIST = 15_000L;

    private static final PvpCredit.Allies NOBODY = (a, b) -> false;
    private static final PvpCredit.Weigher FULL = player -> 1.0;

    @Test
    void aSoloKillCreditsTheKillerWithTheWholeShare() {
        PvpTally tally = new PvpTally();
        tally.add(KILLER, 60.0, NOW - 2_000L);
        tally.add(KILLER, 40.0, NOW);

        PvpCredit.Outcome outcome = PvpCredit.decide(input(KILLER, false, tally, null, false, false), NOBODY, FULL);

        assertEquals(PvpCredit.Refusal.NONE, outcome.refusal());
        assertEquals(KILLER, outcome.killer());
        assertEquals(1, outcome.credits().size());
        PvpCredit.Credit kill = outcome.killCredit();
        assertEquals(KILLER, kill.player());
        assertTrue(kill.killingBlow());
        assertEquals(1.0, kill.share(), 1e-9);
        assertEquals(1.0, kill.weight(), 1e-9);
    }

    @Test
    void everyoneWhoStruckInTheWindowSharesTheKillByDamage() {
        PvpTally tally = new PvpTally();
        tally.add(HELPER, 75.0, NOW - 5_000L);
        tally.add(KILLER, 25.0, NOW);
        tally.add(new UUID(0L, 9L), 500.0, NOW - ASSIST - 1L);

        PvpCredit.Outcome outcome = PvpCredit.decide(input(KILLER, false, tally, null, false, false), NOBODY, FULL);

        assertEquals(2, outcome.credits().size(), "a hit older than the window shares nothing");
        assertEquals(KILLER, outcome.credits().get(0).player(), "the killing blow comes first");
        assertEquals(0.25, outcome.credits().get(0).share(), 1e-9);
        PvpCredit.Credit assist = outcome.credits().get(1);
        assertEquals(HELPER, assist.player());
        assertFalse(assist.killingBlow());
        assertEquals(0.75, assist.share(), 1e-9);
    }

    @Test
    void aDeathThatWasNoOnesIsTheLastStrikersKillOnlyInsideTheWindow() {
        PvpTally tally = new PvpTally();
        tally.add(KILLER, 30.0, NOW - 4_000L);
        UUID lastWithinTen = tally.lastAttacker(NOW, 10_000L);

        PvpCredit.Outcome fell = PvpCredit.decide(input(null, true, tally, lastWithinTen, false, false), NOBODY, FULL);
        assertEquals(KILLER, fell.killer(), "a fall soon after a player's hit is that player's kill");
        assertTrue(fell.killCredit().killingBlow());

        UUID lastWithinTwo = tally.lastAttacker(NOW, 2_000L);
        PvpCredit.Outcome late = PvpCredit.decide(input(null, true, tally, lastWithinTwo, false, false), NOBODY, FULL);
        assertNull(late.killer(), "past the window a fall is nobody's");
        assertEquals(PvpCredit.Refusal.NO_CREDIT, late.refusal());

        PvpCredit.Outcome creature = PvpCredit.decide(input(null, false, tally, lastWithinTen, false, false), NOBODY,
                FULL);
        assertEquals(PvpCredit.Refusal.NO_CREDIT, creature.refusal(), "a creature's kill credits no player");
    }

    @Test
    void anIdleOrFreshlyBackVictimCreditsNothing() {
        PvpTally tally = new PvpTally();
        tally.add(KILLER, 100.0, NOW);

        PvpCredit.Outcome idle = PvpCredit.decide(input(KILLER, false, tally, null, true, false), NOBODY, FULL);
        assertEquals(PvpCredit.Refusal.IDLE, idle.refusal());
        assertTrue(idle.credits().isEmpty());

        PvpCredit.Outcome fresh = PvpCredit.decide(input(KILLER, false, tally, null, false, true), NOBODY, FULL);
        assertEquals(PvpCredit.Refusal.FRESH_SPAWN, fresh.refusal());
        assertTrue(fresh.credits().isEmpty());
    }

    @Test
    void theVictimsAlliesAndTheVictimThemselfAreLeftOut() {
        PvpTally tally = new PvpTally();
        tally.add(HELPER, 50.0, NOW - 1_000L);
        tally.add(PARTY_MATE, 50.0, NOW);
        PvpCredit.Allies party = (a, b) -> Set.of(a, b).equals(Set.of(VICTIM, PARTY_MATE));

        PvpCredit.Outcome mateKilled = PvpCredit.decide(input(PARTY_MATE, false, tally, null, false, false), party,
                FULL);
        assertNull(mateKilled.killCredit(), "a party member's kill credits no killing blow");
        assertEquals(1, mateKilled.credits().size());
        assertEquals(HELPER, mateKilled.credits().get(0).player(), "an enemy who struck still shares it");
        assertFalse(mateKilled.credits().get(0).killingBlow());

        PvpCredit.Outcome self = PvpCredit.decide(input(VICTIM, false, new PvpTally(), null, false, false), NOBODY,
                FULL);
        assertEquals(PvpCredit.Refusal.NO_CREDIT, self.refusal(), "killing yourself credits nobody");
    }

    @Test
    void anAuraOwnerSharesTheKillAtTheAurasShareWhenThatIsMore() {
        PvpTally tally = new PvpTally();
        tally.add(KILLER, 100.0, NOW);
        Map<UUID, Double> auras = Map.of(HELPER, 0.10);

        PvpCredit.Outcome outcome = PvpCredit.decide(new PvpCredit.Input(VICTIM, KILLER, false,
                tally.shares(NOW, ASSIST), null, auras, false, false), NOBODY, FULL);

        assertEquals(2, outcome.credits().size());
        PvpCredit.Credit bearer = outcome.credits().get(1);
        assertEquals(HELPER, bearer.player(), "the flag bearer who never struck still shares the kill");
        assertEquals(0.10, bearer.share(), 1e-9);
        assertFalse(bearer.killingBlow());
    }

    /** The repeat guard: the same player again inside the window credits nothing at all, another player in full. */
    @Test
    void theRepeatGuardCountsEachFallenPlayerApart() {
        DiminishLedger ledger = new DiminishLedger("pvp-credit-test");
        DiminishSpec once = DiminishSpec.rolling(60L * 60L * 1000L, 1.0, 0.0);
        PvpTally tally = new PvpTally();
        tally.add(KILLER, 100.0, NOW);

        PvpCredit.Outcome first = decideWithLedger(VICTIM, tally, ledger, once, NOW);
        assertEquals(1.0, first.killCredit().weight(), 1e-9);

        PvpCredit.Outcome again = decideWithLedger(VICTIM, tally, ledger, once, NOW + 60_000L);
        assertTrue(again.credits().isEmpty(), "the same player again within the hour credits nothing");
        assertEquals(PvpCredit.Refusal.NO_CREDIT, again.refusal());

        PvpCredit.Outcome another = decideWithLedger(OTHER_VICTIM, tally, ledger, once, NOW + 60_000L);
        assertEquals(1.0, another.killCredit().weight(), 1e-9, "another player counts on their own");

        PvpCredit.Outcome later = decideWithLedger(VICTIM, tally, ledger, once, NOW + 61L * 60L * 1000L);
        assertEquals(1.0, later.killCredit().weight(), 1e-9, "past the window the same player counts again");
    }

    @Test
    void aPartialRepeatStepWeighsTheCreditAndKeepsIt() {
        PvpTally tally = new PvpTally();
        tally.add(KILLER, 100.0, NOW);

        PvpCredit.Outcome half = PvpCredit.decide(input(KILLER, false, tally, null, false, false), NOBODY,
                player -> 0.5);

        assertEquals(0.5, half.killCredit().weight(), 1e-9);
    }

    private static PvpCredit.Outcome decideWithLedger(UUID victim, PvpTally tally, DiminishLedger ledger,
            DiminishSpec spec, long now) {
        PvpCredit.Input in = new PvpCredit.Input(victim, KILLER, false, tally.shares(NOW, ASSIST), null, Map.of(),
                false, false);
        return PvpCredit.decide(in, NOBODY, player -> ledger.count(player, "victim:" + victim, spec, now));
    }

    private static PvpCredit.Input input(UUID killer, boolean noOnes, PvpTally tally, UUID lastHitter, boolean idle,
            boolean fresh) {
        Map<UUID, Double> shares = new LinkedHashMap<>(tally.shares(NOW, ASSIST));
        return new PvpCredit.Input(VICTIM, killer, noOnes, shares, lastHitter, Map.of(), idle, fresh);
    }

    @Test
    void aTallyKeepsOnlyItsNewestHits() {
        PvpTally tally = new PvpTally();
        for (int i = 0; i < PvpTally.MAX_ENTRIES + 10; i++) {
            tally.add(i < 10 ? HELPER : KILLER, 1.0, NOW);
        }
        assertEquals(List.of(KILLER), List.copyOf(tally.shares(NOW, ASSIST).keySet()),
                "the oldest hits fall out first");
    }
}
