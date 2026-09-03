package com.ziggfreed.common.objectives.flair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.entity.flair.ZigFlairComponent;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.objectives.flair.FlairUnlocks.Outcome;
import com.ziggfreed.common.subject.Subject;

/**
 * The one write path onto a player's flair set, driven over a bare record with the event family
 * redirected through the publisher seam: what each write answers, and - the half that matters for a
 * listener persisting the set - that only a REAL change is announced.
 *
 * <p>Observed through the seam without building the event: a unit JVM has no live player reference
 * to put in one, and the seam's contract is that nothing is built until somebody listens, so
 * counting what WOULD have gone out is exactly the observation a live listener makes.
 */
class FlairUnlocksTest {

    private final List<Class<?>> announced = new ArrayList<>();
    private ZigFlairComponent flairs;
    private Subject player;

    @BeforeEach
    void setUp() {
        FlairEvents.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                announced.add(type);
            }
        });
        flairs = new ZigFlairComponent();
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    @AfterEach
    void restoreTheBus() {
        FlairEvents.publishTo(null);
    }

    @Test
    void aNewFlairIsUnlockedOnceAndAnnouncedOnce() {
        assertEquals(Outcome.UNLOCKED, FlairUnlocks.write(flairs, player, "Sawmill_Gold", true));
        assertTrue(flairs.hasFlair("sawmill_gold"), "stored lower-cased, as the record does");
        assertEquals(List.of(ZigFlairChangedEvent.class), announced);

        assertEquals(Outcome.ALREADY_UNLOCKED, FlairUnlocks.write(flairs, player, "sawmill_gold", true),
                "granting again is a successful no-op");
        assertEquals(1, announced.size(), "and nothing is announced for a write that changed nothing");
    }

    @Test
    void aRevokeIsAnnouncedOnlyWhenTheFlairWasThere() {
        FlairUnlocks.write(flairs, player, "sawmill_gold", true);
        announced.clear();

        assertEquals(Outcome.REVOKED, FlairUnlocks.write(flairs, player, "SAWMILL_GOLD", false));
        assertFalse(flairs.hasFlair("sawmill_gold"));
        assertEquals(List.of(ZigFlairChangedEvent.class), announced);

        assertEquals(Outcome.NOT_UNLOCKED, FlairUnlocks.write(flairs, player, "sawmill_gold", false));
        assertEquals(1, announced.size(), "taking away what they never had changes nothing");
    }

    @Test
    void anIdTheSaveFormatCannotHoldIsRefusedBeforeAnythingIsWritten() {
        assertEquals(Outcome.REFUSED, FlairUnlocks.write(flairs, player, "bad|id", true));
        assertEquals(Outcome.REFUSED, FlairUnlocks.write(flairs, player, "mod:flair", true));
        assertEquals(Outcome.REFUSED, FlairUnlocks.write(flairs, player, "   ", true));
        assertEquals(Outcome.REFUSED, FlairUnlocks.write(flairs, player, null, true));
        assertTrue(flairs.unlockedFlairs.isEmpty());
        assertTrue(announced.isEmpty(), "a refusal is not a change");
    }

    @Test
    void aPlayerWithNoRecordIsSaidSoRatherThanWrittenNowhere() {
        assertEquals(Outcome.NO_RECORD, FlairUnlocks.write(null, player, "sawmill_gold", true));
        assertEquals(Outcome.NO_RECORD, FlairUnlocks.write(null, player, "sawmill_gold", false));
        assertTrue(announced.isEmpty());
    }

    @Test
    void theTwoChangingOutcomesAreTheTwoAnnouncedOnes() {
        for (Outcome outcome : Outcome.values()) {
            assertEquals(outcome == Outcome.UNLOCKED || outcome == Outcome.REVOKED, outcome.changed(),
                    outcome + " must say whether it changed the set");
        }
    }

    @Test
    void theListingIsSortedAndReadsEmptyForNoRecord() {
        FlairUnlocks.write(flairs, player, "zeta", true);
        FlairUnlocks.write(flairs, player, "Alpha", true);
        FlairUnlocks.write(flairs, player, "mid", true);

        assertEquals(List.of("alpha", "mid", "zeta"), FlairUnlocks.unlockedOf(flairs));
        assertEquals(List.of(), FlairUnlocks.unlockedOf(null));
        assertEquals(List.of(), FlairUnlocks.unlockedOf(new ZigFlairComponent()));
    }
}
