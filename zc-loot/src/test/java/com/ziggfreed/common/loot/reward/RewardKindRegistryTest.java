package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The reward vocabulary: what it starts with, how a consumer extends it, and how lookups behave. */
class RewardKindRegistryTest {

    @Test
    void aRewardVocabularyStartsEmptyAndReportsWhatIsRegistered() {
        RewardKindRegistry registry = new RewardKindRegistry();
        assertTrue(registry.ids().isEmpty());
        assertNull(registry.handler("ANYTHING"));

        registry.register("NOTE", "somebody", (spec, subject) -> { });
        assertTrue(registry.isRegistered("note"));
        assertEquals(List.of("note"), registry.ids());
        assertEquals("somebody", registry.info().get("note").owner());
    }
}
