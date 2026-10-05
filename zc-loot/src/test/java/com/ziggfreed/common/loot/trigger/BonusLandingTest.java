package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

/** Where a broken block's bonus sits, how a harvest's pass waits for the world, and the landed sum. */
class BonusLandingTest {

    @Test
    void aBrokenBlocksBonusSitsHalfABlockInAndOneUp() {
        Vector3d at = BonusPasses.abovePosition(new Vector3i(3, 64, -2));

        assertEquals(3.5, at.x, 1e-9);
        assertEquals(65.0, at.y, 1e-9);
        assertEquals(-1.5, at.z, 1e-9);
    }

    @Test
    void aWorldThatTakesTheTaskQueuesItWithoutRunningIt() {
        List<Runnable> queued = new ArrayList<>();
        AtomicBoolean ran = new AtomicBoolean();

        assertTrue(BonusPasses.afterHarvest(queued::add, () -> ran.set(true)));
        assertEquals(1, queued.size());
        assertFalse(ran.get(), "the pass waits for the world thread, past the engine's own give");
    }

    @Test
    void aWorldThatRefusesTheTaskCostsTheBonusAndNothingElse() {
        Executor closing = task -> {
            throw new RejectedExecutionException("the world is shutting down");
        };

        assertFalse(BonusPasses.afterHarvest(closing, () -> {
            throw new AssertionError("a refused task never runs");
        }));
    }

    @Test
    void theLandedCountAddsUpWhatLanded() {
        Map<String, Integer> landed = new HashMap<>();
        landed.put("Fixture_Gem", 2);
        landed.put("Fixture_Seed", 3);
        landed.put("Fixture_Nothing", null);

        assertEquals(5, BonusPasses.landedCount(landed));
        assertEquals(0, BonusPasses.landedCount(Map.of()));
    }
}
