package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/** The objective vocabulary: what it starts with, how a consumer extends it, and how lookups behave. */
class ObjectiveKindRegistryTest {

    @Test
    void theBuiltInVocabularyIsPresentAndEveryKindIsProducible() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();

        assertEquals(26, registry.ids().size(), "the engine-generic vocabulary");
        for (String id : registry.ids()) {
            ObjectiveKind kind = registry.kind(id);
            assertTrue(kind.producible(), id + " should be authorable");
        }
    }

    /**
     * The seeded arithmetic, stated as sets rather than as one name: the value-based built-ins are
     * exactly the threshold, no built-in reads a ceiling, and everything else accumulates. A new
     * seeded kind must place itself in one of those sentences to pass.
     */
    @Test
    void theThresholdIsTheOneValueBasedBuiltInAndNoBuiltInReadsACeiling() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();

        Set<String> valueBased = new TreeSet<>();
        Set<String> ceilings = new TreeSet<>();
        for (String id : registry.ids()) {
            // The ledger lists ids folded; the kind itself carries the canonical upper-case spelling.
            String canonical = registry.kind(id).id();
            if (registry.isValueBased(id)) {
                valueBased.add(canonical);
            }
            if (registry.isAtMost(id)) {
                ceilings.add(canonical);
            }
        }
        assertEquals(Set.of(ObjectiveKindRegistry.STAT_THRESHOLD), valueBased,
                "a threshold tracks a standing value, so a lower reading must not add to a higher one;"
                        + " every other seeded kind accumulates");
        assertEquals(Set.of(), ceilings,
                "no seeded kind reads its amount as a ceiling; a ceiling kind is a consumer's or a file's to add");
        assertTrue(registry.isProducible(ObjectiveKindRegistry.STAT_THRESHOLD));
    }

    @Test
    void theEncounterKindsAreTheBuiltInsWhoseTargetIsAFight() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();

        Set<String> fights = new TreeSet<>();
        for (String id : registry.ids()) {
            if (registry.isEncounterTargeted(id)) {
                fights.add(registry.kind(id).id());
            }
        }
        assertEquals(Set.of("ENCOUNTER_DEFEATED", "ENCOUNTER_PHASE", "ENCOUNTER_ATTEMPT"), fights,
                "a boss step names the encounter script, and nothing else does");
        for (String id : fights) {
            assertFalse(registry.kind(id).targetsEntity(), id + ": an encounter id is not a creature id");
            assertFalse(registry.kind(id).targetsItem(), id + ": an encounter id is not an item id");
        }
        assertFalse(registry.isEncounterTargeted("NOT_A_KIND"),
                "a kind nobody registered names no fight rather than every fight");
    }

    @Test
    void aConsumerCanRegisterACeilingKindAndTheKnobComposesWithValueBased() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();
        registry.register("mod", ObjectiveKind.atMost("CLEAR_SECONDS"));
        registry.register("mod", ObjectiveKind.of("KILL_FAST").withAtMost(true));

        assertTrue(registry.isValueBased("clear_seconds"));
        assertTrue(registry.isAtMost("clear_seconds"));
        assertFalse(registry.isAtMost("kill_fast"),
                "at-most is a reading of a fired VALUE, so it means nothing on an accumulating kind");
        assertFalse(registry.isAtMost("NOT_A_KIND"));
    }

    @Test
    void talkingAndTravellingAreTheBuiltInsWhoseTargetIsAPlace() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();

        assertTrue(registry.isPlaceTargeted("TALK_TO_NPC"));
        assertTrue(registry.isPlaceTargeted("REACH_LOCATION"));
        assertFalse(registry.isPlaceTargeted("TURN_IN"),
                "a hand-in's target is the thing delivered; where it may be delivered is its own lock");

        for (String id : registry.ids()) {
            if (!id.equalsIgnoreCase("TALK_TO_NPC") && !id.equalsIgnoreCase("REACH_LOCATION")) {
                assertFalse(registry.isPlaceTargeted(id), id + " should name a thing, not a place");
            }
        }
        assertFalse(registry.isPlaceTargeted("NOT_A_KIND"),
                "a kind nobody registered points nowhere rather than everywhere");
    }

    @Test
    void aKindBuiltWithoutThePlaceFlagNamesAThing() {
        assertFalse(new ObjectiveKind("SOMETHING", false, true).targetsPlace());
        assertTrue(ObjectiveKind.placeTargeted("SOMEWHERE").targetsPlace());
    }

    @Test
    void theBuiltInsAreNameableSoAConsumerCanRegisterOnlyWhatItAdds() {
        assertTrue(ObjectiveKindRegistry.isBuiltIn("TALK_TO_NPC"));
        assertTrue(ObjectiveKindRegistry.isBuiltIn(ObjectiveKindRegistry.STAT_THRESHOLD));
        assertTrue(ObjectiveKindRegistry.isBuiltIn("  break_block  "),
                "the same case-and-whitespace blindness every other lookup here has");
        assertFalse(ObjectiveKindRegistry.isBuiltIn("RUN_STREAK"));
        assertFalse(ObjectiveKindRegistry.isBuiltIn(null));
        assertFalse(ObjectiveKindRegistry.isBuiltIn("  "));

        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();
        for (String id : registry.ids()) {
            assertTrue(ObjectiveKindRegistry.isBuiltIn(id), id + " is seeded, so it must answer true");
        }
    }

    @Test
    void lookupsAreCaseAndWhitespaceBlindWhileTheCanonicalIdStaysUpperCase() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();

        assertTrue(registry.isRegistered("break_block"));
        assertTrue(registry.isRegistered("  Break_Block  "));
        assertEquals("BREAK_BLOCK", registry.kind("break_block").id());
    }

    @Test
    void anUnknownKindAnswersNothingRatherThanThrowing() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();

        assertNull(registry.kind("NOT_A_KIND"));
        assertNull(registry.kind(null));
        assertFalse(registry.isRegistered(null));
        assertFalse(registry.isProducible("NOT_A_KIND"));
        assertFalse(registry.isValueBased("NOT_A_KIND"));
    }

    @Test
    void aConsumerCanAddItsOwnKindWithBothKnobsSet() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();
        registry.register("RUN_STREAK", "somebody", true, true);
        registry.register("FUTURE_THING", "somebody", false, false);

        assertTrue(registry.isValueBased("run_streak"));
        assertTrue(registry.isProducible("run_streak"));
        assertTrue(registry.isRegistered("future_thing"));
        assertFalse(registry.isProducible("future_thing"),
                "a kind with no producer must be rejected by a validator");
        assertEquals("somebody", registry.info().get("run_streak").owner());
    }

    @Test
    void aConsumerCanOverrideABuiltInToSayItHasNoProducerHere() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();
        registry.register("CATCH_FISH", "somebody", false, false);

        assertTrue(registry.isRegistered("CATCH_FISH"));
        assertFalse(registry.isProducible("CATCH_FISH"));
    }

    @Test
    void aBlankRegistrationIsIgnoredRatherThanClaimingAnEmptyId() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();
        int before = registry.ids().size();

        registry.register(null);
        registry.register("   ");
        registry.register("somebody", null);

        assertEquals(before, registry.ids().size());
    }

    @Test
    void clearingRestoresTheBuiltInsAndDropsWhatAConsumerAdded() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();
        registry.register("RUN_STREAK", "somebody", true, true);

        registry.clear();

        assertFalse(registry.isRegistered("RUN_STREAK"));
        assertTrue(registry.isRegistered("BREAK_BLOCK"));
    }

    @Test
    void kindFactoriesSetTheKnobsTheyNameAndNormalizeTheId() {
        assertFalse(ObjectiveKind.of(" mine_it ").valueBased());
        assertTrue(ObjectiveKind.of("mine_it").producible());
        assertTrue(ObjectiveKind.valueBased("streak").valueBased());
        assertEquals("MINE_IT", ObjectiveKind.of(" mine_it ").id());
    }
}
