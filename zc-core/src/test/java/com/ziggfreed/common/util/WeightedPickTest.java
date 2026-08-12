package com.ziggfreed.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The picking rules, pinned. Each of these was a place a hand-rolled copy could drift, which is why
 * the primitive exists at all.
 */
class WeightedPickTest {

    /** A sample source that hands back exactly the numbers a case wants, in order. */
    static DoubleSupplier samples(double... values) {
        int[] at = {0};
        return () -> values[Math.min(at[0]++, values.length - 1)];
    }

    record Entry(String id, double weight) {
    }

    static List<Entry> pool() {
        return List.of(new Entry("a", 1.0), new Entry("b", 2.0), new Entry("c", 7.0));
    }

    @Nested
    class OnePick {

        @Test
        void theSampleMapsOntoTheCumulativeWeightBands() {
            // Total is 10: a owns [0,1), b owns [1,3), c owns [3,10).
            assertEquals("a", WeightedPick.one(pool(), Entry::weight, samples(0.05)).id());
            assertEquals("b", WeightedPick.one(pool(), Entry::weight, samples(0.15)).id());
            assertEquals("c", WeightedPick.one(pool(), Entry::weight, samples(0.90)).id());
        }

        @Test
        void aSampleAtTheVeryTopStillPicksSomething() {
            assertEquals("c", WeightedPick.one(pool(), Entry::weight, samples(0.999999999)).id(),
                    "floating-point drift at the top of the range must not answer nothing");
        }

        @Test
        void aNegativeWeightIsNeverPickedRatherThanBendingTheDistribution() {
            List<Entry> pool = List.of(new Entry("penalty", -100.0), new Entry("real", 1.0));
            for (double sample : new double[] {0.0, 0.5, 0.99}) {
                assertEquals("real", WeightedPick.one(pool, Entry::weight, samples(sample)).id());
            }
        }

        @Test
        void allZeroWeightsFallBackToAUniformPickRatherThanGoingDark() {
            List<Entry> pool = List.of(new Entry("a", 0.0), new Entry("b", 0.0), new Entry("c", 0.0));
            assertEquals("a", WeightedPick.one(pool, Entry::weight, samples(0.1)).id());
            assertEquals("b", WeightedPick.one(pool, Entry::weight, samples(0.5)).id());
            assertEquals("c", WeightedPick.one(pool, Entry::weight, samples(0.9)).id());
        }

        @Test
        void anEmptyOrNullPoolPicksNothingWithoutThrowing() {
            assertNull(WeightedPick.one(List.<Entry>of(), Entry::weight, samples(0.5)));
            assertNull(WeightedPick.one(null, Entry::weight, samples(0.5)));
        }

        @Test
        void aWeightFunctionThatThrowsCostsOnlyThatEntry() {
            List<Entry> pool = List.of(new Entry("boom", 1.0), new Entry("fine", 1.0));
            Entry picked = WeightedPick.one(pool, e -> {
                if ("boom".equals(e.id())) {
                    throw new IllegalStateException("weight blew up");
                }
                return e.weight();
            }, samples(0.5));
            assertNotNull(picked);
            assertEquals("fine", picked.id());
        }

        @Test
        void exactlyOneSampleIsConsumedPerPick() {
            int[] draws = {0};
            DoubleSupplier counting = () -> {
                draws[0]++;
                return 0.5;
            };
            WeightedPick.one(pool(), Entry::weight, counting);
            assertEquals(1, draws[0]);
        }
    }

    @Nested
    class ManyPicks {

        @Test
        void withReplacementCanRepeatAnEntry() {
            List<Entry> picks = WeightedPick.some(pool(), Entry::weight, 3, false, samples(0.9, 0.9, 0.9));
            assertEquals(List.of("c", "c", "c"), picks.stream().map(Entry::id).toList());
        }

        @Test
        void uniqueNeverRepeatsAndStopsWhenTheCandidatesRunOut() {
            List<Entry> picks = WeightedPick.some(pool(), Entry::weight, 10, true, samples(0.9, 0.9, 0.9, 0.9));
            assertEquals(3, picks.size(), "a unique draw cannot exceed the candidate count");
            Set<String> ids = new HashSet<>(picks.stream().map(Entry::id).toList());
            assertEquals(3, ids.size(), "every unique pick must be a different entry");
        }

        @Test
        void zeroOrFewerPicksDrawsNothing() {
            assertTrue(WeightedPick.some(pool(), Entry::weight, 0, false, samples(0.5)).isEmpty());
            assertTrue(WeightedPick.some(pool(), Entry::weight, -3, false, samples(0.5)).isEmpty());
        }
    }

    @Nested
    class Determinism {

        @Test
        void theSameSeedYieldsTheSamePicks() {
            List<String> first = pickSeeded(4242L);
            List<String> second = pickSeeded(4242L);
            assertEquals(first, second, "a seeded stream must reproduce exactly");
        }

        @Test
        void aDifferentSeedGenerallyDiverges() {
            assertTrue(!pickSeeded(1L).equals(pickSeeded(999L))
                    || !pickSeeded(2L).equals(pickSeeded(1234L)),
                    "two different seeds must not both reproduce the same run");
        }

        static List<String> pickSeeded(long seed) {
            DoubleSupplier rng = WeightedPick.from(new SplitMix64(seed));
            List<String> out = new ArrayList<>();
            for (Entry picked : WeightedPick.some(pool(), Entry::weight, 12, false, rng)) {
                out.add(picked.id());
            }
            return out;
        }
    }

    @Test
    void totalWeightIgnoresNullsAndNegatives() {
        List<Entry> pool = new ArrayList<>(Arrays.asList(new Entry("a", 3.0), null, new Entry("b", -5.0)));
        assertEquals(3.0, WeightedPick.totalWeight(pool, Entry::weight), 1e-9);
    }
}
