package com.ziggfreed.common.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.junit.jupiter.api.Test;

/**
 * Pure bookkeeping semantics of {@link AppliedEffectTracker}: {@link AppliedEffectTracker#track}
 * never touches the engine (it only records), so these tests exercise the FULL contract with a
 * {@code null} ref/store stand-in - {@code removeAll}'s own engine calls degrade to a no-op via
 * {@link NativeEffectUtil}'s fail-closed guards (see {@link NativeEffectUtilTest}), which is
 * exactly what lets the tracked-set semantics (accumulate, then clear-on-removeAll, regardless of
 * whether any individual remove actually succeeded) be verified here without a live server.
 */
class AppliedEffectTrackerTest {

    private static final Ref<EntityStore> NULL_REF = null;
    private static final Store<EntityStore> NULL_STORE = null;

    @Test
    void freshTracker_isEmpty() {
        AppliedEffectTracker tracker = new AppliedEffectTracker();
        assertTrue(tracker.isEmpty());
        assertEquals(0, tracker.size());
    }

    @Test
    void track_accumulatesEveryEntry_evenRepeats() {
        AppliedEffectTracker tracker = new AppliedEffectTracker();
        tracker.track(NULL_REF, "Root");
        tracker.track(NULL_REF, "Slow");
        tracker.track(NULL_REF, "Root"); // no dedup - a re-apply of the same id tracks again

        assertEquals(3, tracker.size());
        assertTrue(!tracker.isEmpty());
    }

    @Test
    void removeAll_store_clearsTrackedSetRegardlessOfEngineOutcome() {
        AppliedEffectTracker tracker = new AppliedEffectTracker();
        tracker.track(NULL_REF, "Root");
        tracker.track(NULL_REF, "Slow");

        // Each underlying NativeEffectUtil.remove degrades to false (null ref), but removeAll
        // still clears the tracked list unconditionally - a session never re-attempts a stale
        // entry on a later removeAll call.
        tracker.removeAll(NULL_STORE);

        assertTrue(tracker.isEmpty());
        assertEquals(0, tracker.size());
    }

    @Test
    void removeAll_onAnAlreadyEmptyTracker_isANoOp() {
        AppliedEffectTracker tracker = new AppliedEffectTracker();
        tracker.removeAll(NULL_STORE);
        assertTrue(tracker.isEmpty());
    }

    @Test
    void removeAll_thenTrackAgain_startsFresh() {
        AppliedEffectTracker tracker = new AppliedEffectTracker();
        tracker.track(NULL_REF, "Root");
        tracker.removeAll(NULL_STORE);
        assertTrue(tracker.isEmpty());

        tracker.track(NULL_REF, "Freeze");
        assertEquals(1, tracker.size());
    }
}
