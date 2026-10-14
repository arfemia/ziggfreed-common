package com.ziggfreed.common.encounter.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A feat's whole rule, with no fight anywhere near it: which hits break it, which count toward it and
 * which it never judges, and what a win would pay at each point.
 */
class FeatTallyTest {

    private static final EncounterFeat BOMBS_ONLY = EncounterFeat.of("Bombs_Only", List.of("yourmod_Bomb"));
    private static final List<EncounterFeat> FEATS = List.of(BOMBS_ONLY);

    @Test
    void bombHitsOnTheBossHoldItAndOneOtherHitBreaksItForTheWholeFight() {
        FeatTally tally = new FeatTally();
        tally.noteHit(FEATS, FeatTally.Struck.SUBJECT, List.of("yourmod_Bomb"));
        tally.noteHit(FEATS, FeatTally.Struck.SUBJECT, List.of("YOURMOD_BOMB"));
        assertEquals(FeatTally.Standing.HELD, tally.standing(BOMBS_ONLY));
        assertEquals(2, tally.counted(BOMBS_ONLY), "a tag matches whatever its case");
        assertEquals(List.of("Bombs_Only"), tally.held(FEATS), "a win now pays it, under its authored name");

        tally.noteHit(FEATS, FeatTally.Struck.SUBJECT, List.of());
        assertEquals(FeatTally.Standing.BROKEN, tally.standing(BOMBS_ONLY), "an untagged sword hit breaks it");
        tally.noteHit(FEATS, FeatTally.Struck.SUBJECT, List.of("yourmod_Bomb"));
        assertEquals(List.of(), tally.held(FEATS), "nothing mends a broken feat");
    }

    @Test
    void aHitTaggedOnlyWithSomethingElseBreaksIt() {
        FeatTally tally = new FeatTally();
        tally.noteHit(FEATS, FeatTally.Struck.SUBJECT, List.of("yourmod_Bomb"));
        tally.noteHit(FEATS, FeatTally.Struck.SUBJECT, List.of("yourmod_Arrow"));
        assertEquals(FeatTally.Standing.BROKEN, tally.standing(BOMBS_ONLY));
    }

    @Test
    void theAddsMayBeHitWithAnything() {
        FeatTally tally = new FeatTally();
        tally.noteHit(FEATS, FeatTally.Struck.ADD, List.of());
        tally.noteHit(FEATS, FeatTally.Struck.ADD, List.of("yourmod_Arrow"));
        assertEquals(FeatTally.Standing.WAITING, tally.standing(BOMBS_ONLY), "an add's hits are never judged");
        tally.noteHit(FEATS, FeatTally.Struck.SUBJECT, List.of("yourmod_Bomb"));
        assertEquals(List.of("Bombs_Only"), tally.held(FEATS));
    }

    @Test
    void aFeatNoHitEverMetIsNeverPaidAndOneWithNoConditionNeverIs() {
        FeatTally tally = new FeatTally();
        assertEquals(List.of(), tally.held(FEATS), "a boss nobody struck the feat's way pays no feat");

        EncounterFeat blank = EncounterFeat.of("Blank", null);
        tally.noteHit(List.of(blank), FeatTally.Struck.SUBJECT, List.of("yourmod_Bomb"));
        assertEquals(FeatTally.Standing.WAITING, tally.standing(blank));
        assertEquals(List.of(), tally.held(List.of(blank)));
    }

    @Test
    void aRolledOverRunStartsClean() {
        FeatTally tally = new FeatTally();
        tally.noteHit(FEATS, FeatTally.Struck.SUBJECT, List.of());
        tally.clear();
        assertEquals(FeatTally.Standing.WAITING, tally.standing(BOMBS_ONLY));
    }
}
