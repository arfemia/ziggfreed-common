package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * The native-delegation merge ({@code NativeLootService.rollTable}) and the {@code rollNative} engine
 * boundary. A bare unit-test JVM never boots a real {@code ItemModule} (its static {@code get()} is only
 * assigned by the live plugin bootstrap) and cannot construct a real {@link ItemStack} at all here (its
 * codec chain forces {@code Item}'s own codec to initialize, which forces a validator class whose static
 * field requires the Hytale log manager to be installed before ANYTHING touches
 * {@code java.util.logging} - already lost to the Gradle test-worker's own bootstrap before any test code
 * runs; confirmed by direct experiment, not assumed). So the native-item cases stub
 * {@code NativeLootService}'s package-private {@code nativeRewards} seam (native items already mapped to
 * {@link InstanceReward}, no {@link ItemStack} involved) instead of touching the live engine; the
 * unknown-id / disabled-module cases run against the real (unbooted) engine to prove the never-throws /
 * empty-on-disabled contract directly.
 */
class NativeLootServiceTest {

    @AfterEach
    void reset() {
        NativeLootService.resetEngineRollForTesting();
    }

    private static LootTable table(String nativeDropList, String[] guaranteed, String[] pool) {
        return new LootTable(LootEntry.parseAll(guaranteed), LootEntry.parseAll(pool),
                1, 0, 8, "test", "test", nativeDropList);
    }

    @Test
    void nativeTableYieldsUnionOfNativeItemsAndItsOwnEntries() {
        NativeLootService.setNativeRewardsForTesting(id -> "Chase_Nightmare_Items".equals(id)
                ? List.of(InstanceReward.item("KweebecNightmare_Emberbloom", 2, null),
                          InstanceReward.item("KweebecNightmare_Gustbloom", 1, null))
                : List.of());
        LootTable t = table("Chase_Nightmare_Items", new String[]{"currency bounty_token 10"}, new String[]{});

        List<InstanceReward> out = NativeLootService.rollTable(t, 0, true, new Random(1));

        assertEquals(3, out.size());
        assertTrue(out.stream().anyMatch(r -> r.kind() == InstanceReward.Kind.CURRENCY
                && "bounty_token".equals(r.id()) && r.quantity() == 10), "the table's own currency entry must survive");
        assertTrue(out.stream().anyMatch(r -> r.kind() == InstanceReward.Kind.ITEM
                && "KweebecNightmare_Emberbloom".equals(r.id()) && r.quantity() == 2), "a native item must map to an ITEM reward");
        assertTrue(out.stream().anyMatch(r -> r.kind() == InstanceReward.Kind.ITEM
                && "KweebecNightmare_Gustbloom".equals(r.id()) && r.quantity() == 1));
    }

    @Test
    void nativeStepIsNeverConsultedWhenNoDropListIsNamed() {
        // A poisoned stub that would fail the test if it were ever called - proves the null-nativeDropList
        // branch never reaches the native step at all (not merely that it happens to return nothing).
        NativeLootService.setNativeRewardsForTesting(id -> {
            throw new AssertionError("nativeRewards must not be consulted when nativeDropList is absent");
        });
        LootTable t = table(null, new String[]{"item Staple 1"}, new String[]{});
        assertDoesNotThrow(() -> NativeLootService.rollTable(t, 0, true, new Random(1)));
    }

    @Test
    void tableWithoutNativeDropListIsByteForByteUnchanged() {
        LootTable t = table(null, new String[]{"item Staple 1"}, new String[]{"w10 item A 1-3", "w5 item B 2"});

        List<InstanceReward> viaRollTable = NativeLootService.rollTable(t, 2000, true, new Random(777));
        List<InstanceReward> viaPlainRoll = t.roll(2000, true, new Random(777));

        assertEquals(viaPlainRoll, viaRollTable, "no NativeDropList must be a pure pass-through to LootTable.roll");
    }

    @Test
    void blankNativeDropListIsAlsoAPassThrough() {
        LootTable t = table("   ", new String[]{"item Staple 1"}, new String[]{});
        List<InstanceReward> viaRollTable = NativeLootService.rollTable(t, 0, true, new Random(5));
        List<InstanceReward> viaPlainRoll = t.roll(0, true, new Random(5));
        assertEquals(viaPlainRoll, viaRollTable);
    }

    @Test
    void rollNativeReturnsEmptyForUnknownIdWithoutThrowing() {
        // Runs against the REAL (unbooted) engine: ItemModule.get() is null in this JVM, so this exercises
        // the actual production disabled-module guard, not a stub.
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
}
