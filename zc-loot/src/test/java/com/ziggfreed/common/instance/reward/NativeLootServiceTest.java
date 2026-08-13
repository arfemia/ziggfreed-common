package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
}
