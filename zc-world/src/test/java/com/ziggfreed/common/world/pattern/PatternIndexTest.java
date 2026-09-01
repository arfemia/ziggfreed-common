package com.ziggfreed.common.world.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The candidate index: pure storage keyed by exact block item id, plus the radius answer. */
class PatternIndexTest {

    private static BlockPattern<String> pattern(int reach) {
        return BlockPattern.compile(List.of(
                new PatternCell<>(0, 0, 0, "alpha"),
                new PatternCell<>(reach, 0, 0, "beta")), 0, true, false);
    }

    @Test
    void candidatesComeBackUnderTheirIdInRegistrationOrder() {
        PatternIndex<String> index = new PatternIndex<>();
        BlockPattern<String> wide = pattern(3);
        BlockPattern<String> narrow = pattern(1);
        index.add("Block_Alpha", wide, 0, 0);
        index.add("Block_Alpha", narrow, 2, 1);
        index.add("Block_Beta", wide, 1, 1);

        List<PatternIndex.Candidate<String>> alpha = index.candidatesFor("Block_Alpha");
        assertEquals(2, alpha.size());
        assertSame(wide, alpha.get(0).pattern());
        assertEquals(0, alpha.get(0).variantIndex());
        assertEquals(0, alpha.get(0).cellIndex());
        assertSame(narrow, alpha.get(1).pattern());
        assertEquals(2, alpha.get(1).variantIndex());
        assertEquals(1, alpha.get(1).cellIndex());
        assertSame(wide.variants().get(0), alpha.get(0).variant());

        assertEquals(1, index.candidatesFor("Block_Beta").size());
    }

    @Test
    void anUnknownIdAnswersEmptyAndKeysAreCaseSensitive() {
        PatternIndex<String> index = new PatternIndex<>();
        index.add("Block_Alpha", pattern(1), 0, 0);
        assertTrue(index.candidatesFor("Block_Gamma").isEmpty());
        assertTrue(index.candidatesFor("block_alpha").isEmpty(), "asset ids are canonical, exact case");
    }

    @Test
    void aDuplicateRegistrationIsIgnored() {
        PatternIndex<String> index = new PatternIndex<>();
        BlockPattern<String> one = pattern(1);
        index.add("Block_Alpha", one, 0, 0);
        index.add("Block_Alpha", one, 0, 0);
        assertEquals(1, index.candidatesFor("Block_Alpha").size());
    }

    @Test
    void aCandidateRefusesIndexesOutsideItsPattern() {
        PatternIndex<String> index = new PatternIndex<>();
        BlockPattern<String> one = pattern(1);
        assertThrows(IllegalArgumentException.class, () -> index.add("Block_Alpha", one, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> index.add("Block_Alpha", one, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> index.add("Block_Alpha", one, -1, 0));
    }

    @Test
    void maxBoundingRadiusTracksTheWidestRegisteredPattern() {
        PatternIndex<String> index = new PatternIndex<>();
        assertEquals(0, index.maxBoundingRadius());
        index.add("Block_Alpha", pattern(1), 0, 0);
        assertEquals(1, index.maxBoundingRadius());
        index.add("Block_Beta", pattern(5), 0, 0);
        assertEquals(5, index.maxBoundingRadius());
        index.add("Block_Gamma", pattern(2), 0, 0);
        assertEquals(5, index.maxBoundingRadius(), "a narrower pattern never shrinks the answer");
    }

    @Test
    void clearDropsEverythingIncludingTheRadius() {
        PatternIndex<String> index = new PatternIndex<>();
        index.add("Block_Alpha", pattern(4), 0, 0);
        assertFalse(index.isEmpty());
        index.clear();
        assertTrue(index.isEmpty());
        assertTrue(index.candidatesFor("Block_Alpha").isEmpty());
        assertEquals(0, index.maxBoundingRadius());
    }

    @Test
    void theAnswerIsUnmodifiable() {
        PatternIndex<String> index = new PatternIndex<>();
        BlockPattern<String> one = pattern(1);
        index.add("Block_Alpha", one, 0, 0);
        List<PatternIndex.Candidate<String>> answer = index.candidatesFor("Block_Alpha");
        assertThrows(UnsupportedOperationException.class,
                () -> answer.add(new PatternIndex.Candidate<>(one, 0, 0)));
        assertThrows(UnsupportedOperationException.class,
                () -> index.candidatesFor("Block_Gamma").add(new PatternIndex.Candidate<>(one, 0, 0)));
    }
}
