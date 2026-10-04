package com.ziggfreed.common.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ContentText;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.asset.ContentListingAsset.ChainMembership;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * What a yearly copy needs from the runtime object: the occurrence it was minted for, a feat flag
 * read LIVE beside its live availability, copies that never freeze or drop either, and pins that
 * give a slot back once the achievement leaves circulation.
 */
class AchievementOccurrenceRuntimeTest {

    private static final Subject ALICE = new Subject(new UUID(0, 7), "Alice", null);

    private static ObjectiveDef breakAnything(long amount) {
        return ObjectiveDef.builder("one", "BREAK_BLOCK").amount(amount).build();
    }

    private static AchievementEngine engine() {
        return AchievementEngine.builder().nativeEvents(false).build();
    }

    @Test
    void anAchievementNamesTheOccurrenceItWasMintedFor() {
        Achievement minted = Achievement.builder("festival_keeper_2026")
                .occurrence(new Achievement.Occurrence("YourMod_Festival", 2026, "Festival_Keeper"))
                .build();

        assertEquals("yourmod_festival", minted.occurrence().eventId(), "the event id is lower-cased");
        assertEquals(2026, minted.occurrence().year());
        assertEquals("festival_keeper", minted.occurrence().baseId());
        assertNull(Achievement.builder("plain").build().occurrence(), "an ordinary one names none");
    }

    @Test
    void theFeatFlagIsReadLiveWhenTheFoldSuppliesAReading() {
        AtomicBoolean over = new AtomicBoolean(false);
        Achievement achievement = Achievement.builder("festival_keeper_2026").featOfStrength(over::get).build();

        assertFalse(achievement.featOfStrength());
        over.set(true);
        assertTrue(achievement.featOfStrength(), "no rebuild: the flag is asked afresh on every look");
    }

    @Test
    void everyCopyKeepsTheLiveReadingsAndTheOccurrence() {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicBoolean over = new AtomicBoolean(false);
        Achievement original = Achievement.builder("festival_keeper_2026")
                .available(open::get)
                .featOfStrength(over::get)
                .occurrence(new Achievement.Occurrence("yourmod_festival", 2026, "festival_keeper"))
                .metaChildren(List.of("first_child"))
                .chains(List.of(ChainMembership.of("festival", 1)))
                .build();

        Achievement authored = original.withAuthoring(GateSpec.OPEN, ContentText.EMPTY, false, "Fixture_Icon");
        Achievement listed = authored.withListing("fixture", null, 5,
                List.of(ChainMembership.of("festival", 2)));
        Achievement reparented = listed.withMetaChildren(List.of("second_child"));

        open.set(false);
        over.set(true);
        for (Achievement copy : List.of(authored, listed, reparented)) {
            assertFalse(copy.available(), "a copy reads circulation live, as the original does");
            assertTrue(copy.featOfStrength(), "and the feat flag live");
            assertEquals(2026, copy.occurrence().year(), "and keeps the occurrence");
        }
        assertEquals(1, listed.chains().size(), "a listing copy replaces the ladders rather than adding");
        assertEquals(2, listed.primaryChain().tierOrZero());
        assertEquals(List.of("second_child"), reparented.metaChildren(), "the children are replaced whole");
        assertEquals("fixture", reparented.category(), "and everything else carries over");
        assertEquals("Fixture_Icon", reparented.icon());
    }

    @Test
    void progressCountsOnlyWhileTheAchievementIsInCirculation() {
        // Passes before this task's change: it pins the engine behaviour every yearly copy relies on.
        AtomicBoolean open = new AtomicBoolean(false);
        AchievementEngine engine = engine();
        Achievement achievement = Achievement.builder("festival_keeper_2026")
                .available(open::get).criterion(breakAnything(5)).build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Fixture_Block", null, 1L);
        assertEquals(0, engine.progressOf(ALICE, achievement, 0).current(), "closed: nothing counts");
        open.set(true);
        engine.dispatch(ALICE, "BREAK_BLOCK", "Fixture_Block", null, 1L);
        assertEquals(1, engine.progressOf(ALICE, achievement, 0).current(), "open: it counts, no rebuild");
    }

    @Test
    void anAchievementOutOfCirculationCannotBePinned() {
        AtomicBoolean open = new AtomicBoolean(false);
        AchievementEngine engine = engine();
        engine.setAchievements(List.of(Achievement.builder("festival_keeper_2026")
                .available(open::get).criterion(breakAnything(5)).build()));

        assertFalse(engine.pin(ALICE, "festival_keeper_2026"), "nothing to work toward right now");
        open.set(true);
        assertTrue(engine.pin(ALICE, "festival_keeper_2026"));
    }

    @Test
    void aPinOnAnAchievementThatLeftCirculationIsReclaimed() {
        AtomicBoolean open = new AtomicBoolean(true);
        AchievementEngine engine = engine();
        engine.setAchievements(List.of(Achievement.builder("festival_keeper_2026")
                .available(open::get).criterion(breakAnything(5)).build()));
        assertTrue(engine.pin(ALICE, "festival_keeper_2026"));

        open.set(false);
        assertEquals(1, engine.prunePins(ALICE), "a pin on what can no longer be worked toward is dead");
        assertTrue(engine.pinned(ALICE).isEmpty());
    }
}
