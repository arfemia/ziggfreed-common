package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.stats.gearset.GearSetKeys.TierRef;
import com.ziggfreed.common.stats.gearset.GearSets.TierFlip;

/**
 * Only a real flip is announced, and each flip goes out as one event through the seam.
 *
 * <p>The flip arithmetic is pure and pinned directly; the fire is observed through the
 * {@link NativeEventSeam.Publisher} seam without building the event, because a unit JVM has no live
 * player reference to put in one, and the seam's contract is that nothing is built until somebody
 * listens. The one case that DOES build it shows the refusal a player-less fire makes, inside the
 * seam's own guard.
 */
class GearSetEventsTest {

    private final List<Class<?>> announced = new ArrayList<>();
    private final List<Supplier<? extends IEvent<Void>>> builders = new ArrayList<>();

    @BeforeEach
    void redirectTheBus() {
        GearSetEvents.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                announced.add(type);
                builders.add(build);
            }
        });
    }

    @AfterEach
    void restoreTheBus() {
        GearSetEvents.publishTo(null);
    }

    @Test
    void theSameTiersActiveTwiceIsNoFlip() {
        Set<TierRef> tiers = Set.of(new TierRef("night_set", 0), new TierRef("night_set", 1));
        assertTrue(GearSets.flips(tiers, tiers).isEmpty());
        assertTrue(GearSets.flips(List.of(), List.of()).isEmpty());
    }

    @Test
    void aTierThatWentOffAndOneThatCameOnAreTwoFlipsOffFirst() {
        List<TierRef> before = List.of(new TierRef("night_set", 0), new TierRef("night_set", 2));
        List<TierRef> now = List.of(new TierRef("night_set", 0), new TierRef("night_set", 1));

        assertEquals(List.of(new TierFlip(new TierRef("night_set", 2), false),
                new TierFlip(new TierRef("night_set", 1), true)), GearSets.flips(before, now));
    }

    @Test
    void eachFireIsOneEventOfTheOneType() {
        GearSetAsset set = GearSetAsset.of("Night_Set", null, null, new String[] {"A", "B"},
                GearSetAsset.Tier.of(2, null, null, null, null, null, null));

        GearSetEvents.fireTierChanged(UUID.randomUUID(), null, set, 0, true, 2);
        GearSetEvents.fireTierChanged(UUID.randomUUID(), null, set, 0, false, 1);

        assertEquals(List.of(ZigGearSetTierChangedEvent.class, ZigGearSetTierChangedEvent.class), announced);
    }

    @Test
    void aFireWithNoLivePlayerRefusesToBuildAHalfEventAndTheSeamKeepsTheRefusal() {
        GearSetAsset set = GearSetAsset.of("Night_Set", null, null, new String[] {"A", "B"},
                GearSetAsset.Tier.of(2, null, null, null, null, null, null));

        GearSetEvents.fireTierChanged(UUID.randomUUID(), null, set, 0, true, 2);

        assertEquals(1, builders.size());
        assertThrows(IllegalStateException.class, () -> builders.get(0).get(),
                "the event carries a live reference or is not built at all");
    }

    @Test
    void nothingGoesOutForAPlayerWhoseTiersDidNotChange() {
        // The engine fires one event per flip and none otherwise: with no flips, no fire.
        for (TierFlip flip : GearSets.flips(List.of(new TierRef("night_set", 0)), List.of(new TierRef("night_set", 0)))) {
            GearSetEvents.fireTierChanged(UUID.randomUUID(), null,
                    GearSetAsset.of("Night_Set", null, null, null), flip.tier().tierIndex(), flip.active(), 1);
        }
        assertTrue(announced.isEmpty());
    }
}
