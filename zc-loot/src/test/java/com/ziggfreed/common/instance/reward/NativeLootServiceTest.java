package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * The {@code rollNative} engine boundary: it answers something, and it never throws.
 *
 * <p>A bare unit-test JVM never boots a real {@code ItemModule} (its static {@code get()} is only
 * assigned by the live plugin bootstrap) and cannot construct a real {@link ItemStack} at all here (its
 * codec chain forces {@code Item}'s own codec to initialize, which forces a validator class whose static
 * field requires the Hytale log manager to be installed before ANYTHING touches
 * {@code java.util.logging} - already lost to the Gradle test-worker's own bootstrap before any test code
 * runs; confirmed by direct experiment, not assumed). So these cases run against the real (unbooted)
 * engine, proving the never-throws / empty-on-disabled contract directly; what a live drop list actually
 * produces is an in-game question.
 */
class NativeLootServiceTest {

    @AfterEach
    void reset() {
        NativeLootService.resetEngineRollForTesting();
    }

    @Test
    void rollNativeReturnsEmptyForUnknownIdWithoutThrowing() {
        assertDoesNotThrow(() -> {
            List<ItemStack> items = NativeLootService.rollNative("totally_unknown_drop_list_xyz");
            assertNotNull(items);
            assertTrue(items.isEmpty());
        });
    }

    @Test
    void rollNativeReturnsEmptyForBlankId() {
        assertEquals(List.of(), NativeLootService.rollNative(""));
        assertEquals(List.of(), NativeLootService.rollNative("   "));
    }

    @Test
    void rollNativeNeverThrowsEvenWhenTheEngineRollThrows() {
        NativeLootService.setEngineRollForTesting(id -> {
            throw new IllegalStateException("boom");
        });
        assertDoesNotThrow(() -> assertTrue(NativeLootService.rollNative("anything").isEmpty()));
    }

    @Test
    void aStubbedRollAnswersWhatTheEngineWouldHaveAnswered() {
        NativeLootService.setEngineRollForTesting(id -> List.of());
        assertEquals(List.of(), NativeLootService.rollNative("Chase_Nightmare_Items"));
    }

    /**
     * An empty stack list is a no-op that answers LANDED, and the fast path must decide that before
     * touching any accessor: that is what makes every spawn form safe to call unconditionally from a
     * payout path that may have rolled nothing. The nulls stand in for the engine handles a bare
     * unit-test JVM cannot construct; the contract under test is exactly that an empty spawn never
     * dereferences them.
     */
    @Test
    void anEmptySpawnAnswersLandedWithoutTouchingTheAccessors() {
        assertDoesNotThrow(() -> {
            assertTrue(NativeLootService.spawnInWorld(null, new Vector3d(), Rotation3f.IDENTITY, List.of()));
            assertTrue(NativeLootService.spawnInWorld(null, null, new Vector3d(), new Rotation3f(), List.of()));
            assertTrue(NativeLootService.spawnAtFeet(null, List.of()));
        });
    }
}
