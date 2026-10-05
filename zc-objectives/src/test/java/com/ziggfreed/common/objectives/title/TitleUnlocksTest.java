package com.ziggfreed.common.objectives.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.entity.title.ZigTitleComponent;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.objectives.title.TitleUnlocks.Outcome;
import com.ziggfreed.common.subject.Subject;

/**
 * The one write path onto a player's titles, over a bare record with the event redirected through
 * the publisher seam: what each write answers, that only a REAL change is announced, and that every
 * change leaves the off-thread mirror saying what the record says.
 */
class TitleUnlocksTest {

    private final List<Class<?>> announced = new ArrayList<>();
    private ZigTitleComponent titles;
    private Subject player;

    @BeforeEach
    void setUp() {
        TitleEvents.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                announced.add(type);
            }
        });
        titles = new ZigTitleComponent();
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    @AfterEach
    void restore() {
        TitleEvents.publishTo(null);
        ActiveTitles.clear();
    }

    @Test
    void aNewTitleIsUnlockedOnceAndAnnouncedOnce() {
        assertEquals(Outcome.UNLOCKED, TitleUnlocks.write(titles, player, "Hallows_Eve_Hallowed", true));
        assertTrue(titles.hasTitle("hallows_eve_hallowed"));
        assertEquals(List.of(ZigTitleChangedEvent.class), announced);

        assertEquals(Outcome.ALREADY_UNLOCKED, TitleUnlocks.write(titles, player, "hallows_eve_hallowed", true),
                "granting again is a successful no-op");
        assertEquals(1, announced.size(), "a write that changed nothing announces nothing");
    }

    @Test
    void showingATitleNeedsItUnlockedAndKeepsTheMirrorCurrent() {
        assertEquals(Outcome.NOT_UNLOCKED, TitleUnlocks.activateWrite(titles, player, "hallows_eve_hallowed"));
        assertNull(ActiveTitles.of(player.id()));

        TitleUnlocks.write(titles, player, "hallows_eve_hallowed", true);
        announced.clear();
        assertEquals(Outcome.ACTIVATED, TitleUnlocks.activateWrite(titles, player, "HALLOWS_EVE_HALLOWED"));
        assertEquals("hallows_eve_hallowed", ActiveTitles.of(player.id()),
                "a menu on another world thread reads it at once");
        assertEquals(List.of(ZigTitleChangedEvent.class), announced);

        assertEquals(Outcome.ALREADY_ACTIVE, TitleUnlocks.activateWrite(titles, player, "hallows_eve_hallowed"));
        assertEquals(1, announced.size());
    }

    @Test
    void takingTheShownTitleOffClearsTheMirrorAndASecondTakeOffIsQuiet() {
        TitleUnlocks.write(titles, player, "pumpkin_king", true);
        TitleUnlocks.activateWrite(titles, player, "pumpkin_king");
        announced.clear();

        assertEquals(Outcome.DEACTIVATED, TitleUnlocks.deactivateWrite(titles, player));
        assertNull(ActiveTitles.of(player.id()));
        assertEquals(List.of(ZigTitleChangedEvent.class), announced);

        assertEquals(Outcome.NONE_ACTIVE, TitleUnlocks.deactivateWrite(titles, player));
        assertEquals(1, announced.size());
    }

    @Test
    void revokingTheShownTitleTakesItOffEverywhere() {
        TitleUnlocks.write(titles, player, "pumpkin_king", true);
        TitleUnlocks.activateWrite(titles, player, "pumpkin_king");

        assertEquals(Outcome.REVOKED, TitleUnlocks.write(titles, player, "Pumpkin_King", false));
        assertNull(titles.activeTitle());
        assertNull(ActiveTitles.of(player.id()));
        assertEquals(Outcome.NOT_UNLOCKED, TitleUnlocks.write(titles, player, "pumpkin_king", false));
    }

    @Test
    void anIdTheSaveFormatCannotHoldIsRefusedBeforeAnythingIsWritten() {
        for (String bad : new String[] {"bad|id", "mod:title", "   "}) {
            assertEquals(Outcome.REFUSED, TitleUnlocks.write(titles, player, bad, true), bad);
            assertEquals(Outcome.REFUSED, TitleUnlocks.activateWrite(titles, player, bad), bad);
        }
        assertEquals(Outcome.REFUSED, TitleUnlocks.write(titles, player, null, true));
        assertTrue(titles.unlockedTitles.isEmpty());
        assertTrue(announced.isEmpty(), "a refusal is not a change");
    }

    @Test
    void aPlayerWithNoRecordIsSaidSoRatherThanWrittenNowhere() {
        assertEquals(Outcome.NO_RECORD, TitleUnlocks.write(null, player, "pumpkin_king", true));
        assertEquals(Outcome.NO_RECORD, TitleUnlocks.activateWrite(null, player, "pumpkin_king"));
        assertEquals(Outcome.NO_RECORD, TitleUnlocks.deactivateWrite(null, player));
        assertTrue(announced.isEmpty());
    }

    @Test
    void theFourChangingOutcomesAreTheFourAnnouncedOnes() {
        Set<Outcome> changing = EnumSet.of(Outcome.UNLOCKED, Outcome.REVOKED, Outcome.ACTIVATED,
                Outcome.DEACTIVATED);
        for (Outcome outcome : Outcome.values()) {
            assertEquals(changing.contains(outcome), outcome.changed(),
                    outcome + " must say whether it changed the record");
        }
    }

    @Test
    void theListingIsSortedAndReadsEmptyForNoRecord() {
        TitleUnlocks.write(titles, player, "zeta", true);
        TitleUnlocks.write(titles, player, "Alpha", true);

        assertEquals(List.of("alpha", "zeta"), TitleUnlocks.unlockedOf(titles));
        assertEquals(List.of(), TitleUnlocks.unlockedOf(null));
    }
}
