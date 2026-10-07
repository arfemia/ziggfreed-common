package com.ziggfreed.common.objectives.book.achievement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * Where the achievement tab offers Pin, away from the page that paints it (no test reaches a page:
 * its static logger needs the engine's log manager). The rule: never on a feat; otherwise where a pin
 * is held, so it can come off, or where the engine's pin would take it short of the cap.
 */
class AchievementPinOfferTest {

    private static final Subject ALICE = new Subject(new UUID(0, 9), "Alice", null);

    @Test
    void aFeatNeverOffersPin() {
        assertFalse(AchievementPinOffer.offersPin(true, false, true), "an earned trophy tracks nothing");
        assertFalse(AchievementPinOffer.offersPin(true, true, true), "not even with a pin held");
        assertFalse(AchievementPinOffer.offersPin(true, true, false));
    }

    @Test
    void aHeldPinAlwaysOffersSoItCanComeOff() {
        assertTrue(AchievementPinOffer.offersPin(false, true, false),
                "earned or out of circulation since it was pinned: the pin still comes off");
        assertTrue(AchievementPinOffer.offersPin(false, true, true));
    }

    @Test
    void otherwiseTheOfferFollowsWhatTheEnginesPinWouldTakeShortOfTheCap() {
        assertTrue(AchievementPinOffer.offersPin(false, false, true),
                "pinnable, at the cap too: the cap is then the true refusal");
        assertFalse(AchievementPinOffer.offersPin(false, false, false), "the engine would refuse it");
    }

    @Test
    void anEarnedAchievementOutOfCirculationThatIsNotPinnedOffersNothing() {
        AtomicBoolean open = new AtomicBoolean(true);
        AchievementEngine engine = AchievementEngine.builder().nativeEvents(false).build();
        Achievement retired = Achievement.builder("retired").available(open::get).build();
        engine.setAchievements(List.of(retired));
        assertTrue(engine.unlock(ALICE, retired));
        open.set(false);

        assertFalse(retired.featOfStrength(), "not a feat, so a feat-only rule would still offer Pin");
        assertFalse(AchievementPinOffer.offersPin(retired.featOfStrength(),
                engine.pinned(ALICE).contains(retired.id()), engine.pinnable(ALICE, retired.id())),
                "the engine refuses it, so the book offers nothing for a refusal to blame on the cap");
    }
}
