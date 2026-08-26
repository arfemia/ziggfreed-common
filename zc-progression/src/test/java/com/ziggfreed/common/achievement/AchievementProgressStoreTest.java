package com.ziggfreed.common.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;

/**
 * The persistence seam's shared rules, exercised against the in-memory implementation: the composite
 * criterion key (achievement id joined to the CRITERION ID), and what clearing an achievement
 * actually clears.
 *
 * <p>Every assertion goes through an {@link AchievementProgressStore}-typed reference, so a change
 * that breaks the seam is a compile failure rather than a silently different behaviour.
 */
class AchievementProgressStoreTest {

    private static final Subject ALICE = new Subject(new UUID(0, 1), "Alice", null);
    private static final Subject BOB = new Subject(new UUID(0, 2), "Bob", null);

    private AchievementProgressStore store() {
        return new InMemoryAchievementProgressStore();
    }

    @Test
    void criterionProgressIsKeyedByCriterionIdUnderTheCompositeKey() {
        AchievementProgressStore store = store();
        store.setCriterionProgress(ALICE, "prospector", "mine-copper", 7L);
        store.setCriterionProgress(ALICE, "prospector", "gather-copper", 3L);

        assertEquals(7L, store.criterionProgress(ALICE, "prospector", "mine-copper"));
        assertEquals(3L, store.criterionProgress(ALICE, "prospector", "gather-copper"));
        assertEquals(0L, store.criterionProgress(ALICE, "prospector", "smelt-copper"),
                "an untouched criterion is zero");
        assertEquals(7L,
                store.progress(ALICE, AchievementProgressStore.criterionKey("prospector", "mine-copper")),
                "the composite key is what is actually stored");
        assertEquals(0L, store.criterionProgress(BOB, "prospector", "mine-copper"),
                "subjects do not share progress");
    }

    @Test
    void writingZeroRemovesTheKeyRatherThanStoringAZero() {
        AchievementProgressStore store = store();
        store.setCriterionProgress(ALICE, "prospector", "mine-copper", 50L);
        assertEquals(50L, store.criterionProgress(ALICE, "prospector", "mine-copper"));

        store.setCriterionProgress(ALICE, "prospector", "mine-copper", 0L);
        assertEquals(0L, store.criterionProgress(ALICE, "prospector", "mine-copper"));
        assertFalse(store.progressKeys(ALICE)
                        .contains(AchievementProgressStore.criterionKey("prospector", "mine-copper")),
                "a reset criterion leaves no key behind");
    }

    @Test
    void clearingAnAchievementTakesEveryTraceOfIt() {
        AchievementProgressStore store = store();
        // The bare id is not a key anything writes any more, but a clear still sweeps one a very
        // old save might carry, so nothing can outlive the start-over.
        store.putProgress(ALICE, "prospector", 5L);
        store.setCriterionProgress(ALICE, "prospector", "gather-copper", 3L);
        store.setStatus(ALICE, "prospector", AchievementStatus.UNLOCKED);
        store.setUnlockedAt(ALICE, "prospector", 1234L);
        store.setPin(ALICE, "prospector", 99L);
        store.setCriterionProgress(ALICE, "wanderer", "walk", 8L);

        store.clearAchievement(ALICE, "prospector");

        assertEquals(0L, store.progress(ALICE, "prospector"));
        assertEquals(0L, store.criterionProgress(ALICE, "prospector", "gather-copper"));
        assertEquals(AchievementStatus.LOCKED, store.status(ALICE, "prospector"));
        assertEquals(0L, store.unlockedAt(ALICE, "prospector"));
        assertFalse(store.pins(ALICE).containsKey("prospector"));
        assertEquals(8L, store.criterionProgress(ALICE, "wanderer", "walk"),
                "clearing one achievement never touches another");
    }

    @Test
    void statusUnlockInstantMilestonesAndPinsRoundTrip() {
        AchievementProgressStore store = store();

        store.setStatus(ALICE, "prospector", AchievementStatus.UNLOCKED);
        assertEquals(AchievementStatus.UNLOCKED, store.status(ALICE, "prospector"));
        assertTrue(store.status(ALICE, "prospector").isUnlocked());
        assertFalse(store.status(ALICE, "prospector").isClaimed());

        store.setStatus(ALICE, "prospector", AchievementStatus.CLAIMED);
        assertTrue(store.status(ALICE, "prospector").isClaimed());
        assertEquals(AchievementStatus.LOCKED, store.status(ALICE, "unknown"), "an unknown id reads LOCKED");

        store.setUnlockedAt(ALICE, "prospector", 5150L);
        assertEquals(5150L, store.unlockedAt(ALICE, "prospector"));

        store.setMilestoneStatus(ALICE, 100, AchievementStatus.UNLOCKED);
        assertEquals(AchievementStatus.UNLOCKED, store.milestoneStatus(ALICE, 100));
        assertEquals(AchievementStatus.LOCKED, store.milestoneStatus(ALICE, 200));
        assertTrue(store.knownMilestones(ALICE).contains(100));

        store.setPin(ALICE, "wanderer", 7L);
        assertEquals(7L, store.pins(ALICE).get("wanderer"));
        assertTrue(store.clearPin(ALICE, "wanderer"));
        assertFalse(store.clearPin(ALICE, "wanderer"));
    }

    @Test
    void knownAchievementIdsCoversProgressAndStatusAlike() {
        AchievementProgressStore store = store();
        store.setCriterionProgress(ALICE, "prospector", "gather-copper", 3L);
        store.setStatus(ALICE, "wanderer", AchievementStatus.CLAIMED);

        assertTrue(store.knownAchievementIds(ALICE).contains("prospector"),
                "an id known only from a composite progress key still counts");
        assertTrue(store.knownAchievementIds(ALICE).contains("wanderer"));
    }

    @Test
    void reservedDelimitersAreRejectedSoProgressCannotBeSilentlyLost() {
        AchievementProgressStore store = store();
        assertTrue(store.usesReservedDelimiter("has#hash"), "the criterion separator cannot be in an id");
        assertTrue(store.usesReservedDelimiter("has|pipe"));
        assertTrue(store.usesReservedDelimiter("has:colon"));
        assertTrue(store.usesReservedDelimiter("  "));
        assertTrue(store.usesReservedDelimiter(null));
        assertFalse(store.usesReservedDelimiter("plain_id"));
    }
}
