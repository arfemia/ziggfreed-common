package com.ziggfreed.common.effect.costume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.effect.costume.CostumeRules.Look;
import com.ziggfreed.common.effect.costume.CostumeRules.Verdict;

/** What a costume is, when one may go on, and what taking costumes off takes. */
class CostumeRulesTest {

    private static final Look GHOST = new Look("Yourpack_Costume_Ghost", "Ghost", false);
    private static final Look FROG = new Look("Potion_Morph_Frog", "Frog_Green", false);
    private static final Look CURSE = new Look("Yourpack_Curse_Rat", "Rat", true);
    private static final Look HASTE = new Look("Yourpack_Haste", null, false);

    @Test
    void aModelChangeThatIsNoDebuffIsACostume() {
        assertTrue(GHOST.isCostume());
        assertTrue(FROG.isCostume(), "a morph a player drank is theirs to end early too");
        assertFalse(CURSE.isCostume());
        assertTrue(CURSE.locksModel(), "a debuff transformation is never the wearer's to take off");
        assertFalse(HASTE.isCostume());
        assertFalse(new Look("Yourpack_Blank", "  ", false).isCostume(), "a blank model id changes nothing");
    }

    @Test
    void aWearerInNoCostumeIsDressed() {
        assertEquals(Verdict.DRESS, CostumeRules.dress(GHOST, List.of()));
        assertEquals(Verdict.DRESS, CostumeRules.dress(GHOST, List.of(HASTE)),
                "an effect that changes no model is no obstacle");
    }

    @Test
    void theSameCostumeAgainIsRefusedAsAlreadyWorn() {
        assertEquals(Verdict.ALREADY_WORN, CostumeRules.dress(GHOST, List.of(GHOST)),
                "a re-dress changes nothing, so whatever pays for a costume landing never pays twice");
        assertEquals(Verdict.ALREADY_WORN,
                CostumeRules.dress(GHOST, List.of(new Look("yourpack_costume_ghost", "Ghost", false))),
                "the worn costume matches whatever case either side wrote its id in");
    }

    @Test
    void aLockedWearerReadsLockedEvenInTheSameCostume() {
        assertEquals(Verdict.LOCKED, CostumeRules.dress(GHOST, List.of(GHOST, CURSE)),
                "a transformation the wearer cannot take off answers first, whatever else they wear");
        assertEquals(Verdict.LOCKED, CostumeRules.dress(GHOST, List.of(CURSE, GHOST)));
    }

    @Test
    void aWearerInThisCostumeAndAnotherStillReadsWearingAnother() {
        assertEquals(Verdict.WEARING_ANOTHER, CostumeRules.dress(GHOST, List.of(GHOST, FROG)),
                "another costume beside this one answers as it always has, in either order");
        assertEquals(Verdict.WEARING_ANOTHER, CostumeRules.dress(GHOST, List.of(FROG, GHOST)));
    }

    @Test
    void aDifferentCostumeWaitsUntilTheFirstIsOff() {
        assertEquals(Verdict.WEARING_ANOTHER, CostumeRules.dress(GHOST, List.of(FROG)),
                "the engine would restore the first costume's model when the second ended");
    }

    @Test
    void aTransformationTheWearerCannotTakeOffRefusesEveryCostume() {
        assertEquals(Verdict.LOCKED, CostumeRules.dress(GHOST, List.of(CURSE)));
        assertEquals(Verdict.LOCKED, CostumeRules.dress(GHOST, List.of(FROG, CURSE)));
    }

    @Test
    void onlyACostumeIsEverPutOnAsOne() {
        assertEquals(Verdict.NOT_A_COSTUME, CostumeRules.dress(HASTE, List.of()), "it changes no model");
        assertEquals(Verdict.NOT_A_COSTUME, CostumeRules.dress(CURSE, List.of()),
                "a debuff could never be taken off, so it is never a costume");
    }

    @Test
    void takingCostumesOffTakesEveryCostumeOnceAndNothingElse() {
        assertEquals(List.of("Yourpack_Costume_Ghost", "Potion_Morph_Frog"),
                CostumeRules.costumesIn(List.of(GHOST, HASTE, CURSE, FROG,
                        new Look("YOURPACK_COSTUME_GHOST", "Ghost", false))));
        assertEquals(List.of(), CostumeRules.costumesIn(List.of(HASTE, CURSE)));
    }

    @Test
    void nobodyDressesThemselvesButAnyoneDressesAnotherPlayer() {
        UUID ann = UUID.randomUUID();
        UUID bo = UUID.randomUUID();

        assertTrue(CostumeRules.dressingYourself(ann, ann));
        assertFalse(CostumeRules.dressingYourself(ann, bo));
        assertFalse(CostumeRules.dressingYourself(null, bo), "a chain no player owns may dress anybody");
    }
}
