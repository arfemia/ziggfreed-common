package com.ziggfreed.common.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pool a heal's credit is drawn from (R788) guards an exploit: healing XP may never pay for more creature damage
 * than was taken, nor for damage taken longer ago than the window, nor for damage some other heal already gave back.
 * So the draw-down, the fade and the hold to the health still missing are pinned here, on the pure pool alone.
 */
class HostileDamagePoolTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void aHealDrawsThePoolDownAndNeverPaysPastIt() {
        HostileDamagePool pool = new HostileDamagePool();
        pool.add(30.0, 0L);

        assertEquals(20.0, pool.draw(20.0, 100L), EPSILON, "a heal under the pool is credited whole");
        assertEquals(10.0, pool.held(100L), EPSILON, "and the pool is down by what it gave");
        assertEquals(10.0, pool.draw(50.0, 200L), EPSILON, "a heal past the pool is credited only what is left");
        assertEquals(0.0, pool.draw(50.0, 300L), EPSILON, "an empty pool credits nothing");
        assertTrue(pool.isEmpty(300L));
        assertEquals(0.0, pool.draw(-5.0, 300L), EPSILON, "a heal that restores nothing draws nothing");
    }

    @Test
    void aHitFadesWholeOnceTheWindowHasPassed() {
        HostileDamagePool pool = new HostileDamagePool();
        pool.add(40.0, 1_000L);

        assertEquals(40.0, pool.held(1_000L + HostileDamagePool.WINDOW_MS - 1L), EPSILON,
                "a hit is still creditable just inside the window");
        assertEquals(0.0, pool.held(1_000L + HostileDamagePool.WINDOW_MS), EPSILON,
                "and gone once the window has passed");
        assertEquals(0.0, pool.draw(40.0, 1_000L + HostileDamagePool.WINDOW_MS), EPSILON,
                "so a heal after it is credited nothing");
    }

    @Test
    void theOldestHitsAreDrawnFirstAndEachFadesOnItsOwnClock() {
        HostileDamagePool pool = new HostileDamagePool();
        pool.add(30.0, 0L);
        pool.add(20.0, 5_000L);

        assertEquals(40.0, pool.draw(40.0, 6_000L), EPSILON);
        // The first hit was spent whole, and 10 of the second is left.
        assertEquals(10.0, pool.held(HostileDamagePool.WINDOW_MS + 1L), EPSILON,
                "what is left of the newer hit outlives the older one's window");
        assertEquals(0.0, pool.held(5_000L + HostileDamagePool.WINDOW_MS), EPSILON,
                "and fades on its own");
    }

    @Test
    void healthAPotionGaveBackIsNeverCreditedAgain() {
        HostileDamagePool pool = new HostileDamagePool();
        // A creature takes 10 of 100 health, and a potion puts the player back to full.
        pool.add(10.0, 0L);
        // Then a fall takes 10 at full health: read after it, they stand at 90 of 100.
        double missing = HostileDamagePool.missingBefore(90.0, 100.0, 10.0);
        assertEquals(0.0, missing, EPSILON, "a hit taken at full health was missing nothing before it");
        pool.clampTo(missing, 1_000L);
        assertEquals(0.0, pool.draw(10.0, 2_000L), EPSILON, "so a spell healing the fall back is credited nothing");
    }

    @Test
    void thePoolIsHeldToTheHealthStillMissing() {
        HostileDamagePool pool = new HostileDamagePool();
        pool.add(30.0, 0L);

        pool.clampTo(12.0, 100L);
        assertEquals(12.0, pool.held(100L), EPSILON, "health regained some other way leaves the pool");
        pool.clampTo(Double.NaN, 200L);
        assertEquals(12.0, pool.held(200L), EPSILON, "an unreadable health holds nothing back");
        pool.clampTo(20.0, 300L);
        assertEquals(12.0, pool.held(300L), EPSILON, "more missing than held changes nothing");
        assertEquals(12.0, pool.draw(30.0, 400L), EPSILON);
        assertTrue(pool.isEmpty(400L));
    }

    @Test
    void hitsInOneMomentJoinOneEntryAndStillAddUp() {
        HostileDamagePool pool = new HostileDamagePool();
        for (int i = 0; i < 1_000; i++) {
            pool.add(1.0, i * 5L);
        }
        assertEquals(1_000.0, pool.held(5_000L), EPSILON, "joined hits keep every point");
        assertEquals(0.0, pool.held(4_995L + HostileDamagePool.WINDOW_MS + HostileDamagePool.BUCKET_MS), EPSILON,
                "and fade no later than their own window");
    }
}
