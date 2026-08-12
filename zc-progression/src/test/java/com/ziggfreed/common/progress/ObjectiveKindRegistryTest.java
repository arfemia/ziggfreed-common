package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The objective vocabulary: what it starts with, how a consumer extends it, and how lookups behave. */
class ObjectiveKindRegistryTest {

    @Test
    void theBuiltInVocabularyIsPresentAndEveryKindIsProducible() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();

        assertEquals(21, registry.ids().size(), "the engine-generic vocabulary");
        for (String id : registry.ids()) {
            ObjectiveKind kind = registry.kind(id);
            assertTrue(kind.producible(), id + " should be authorable");
        }
    }

    @Test
    void theThresholdKindIsTheOneValueBasedBuiltIn() {
        ObjectiveKindRegistry registry = new ObjectiveKindRegistry();

        assertTrue(registry.isRegistered(ObjectiveKindRegistry.STAT_THRESHOLD));
        assertTrue(registry.isValueBased(ObjectiveKindRegistry.STAT_THRESHOLD),
                "a threshold tracks a standing value, so a lower reading must not add to a higher one");
        assertTrue(registry.isProducible(ObjectiveKindRegistry.STAT_THRESHOLD));

        for (String id : registry.ids()) {
            if (!id.equalsIgnoreCase(ObjectiveKindRegistry.STAT_THRESHOLD)) {
                assertFalse(registry.isValueBased(id), id + " should accumulate");
            }
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
