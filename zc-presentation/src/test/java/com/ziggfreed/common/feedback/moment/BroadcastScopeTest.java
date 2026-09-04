package com.ziggfreed.common.feedback.moment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The four scoping leaves of a banner, each on its own and together, over the codec that reads them:
 * who is shown a banner, that an unauthored leaf is today's every-online fan-out, that an overlay
 * keeps the leaves it did not mention, and that the throttle holds a banner back per key.
 */
class BroadcastScopeTest {

    private static final double NEAR = 10.0;
    private static final double FAR = 500.0;
    private static final double UNKNOWN = Double.NaN;

    @Nonnull
    private static FeedbackMomentAsset.Broadcast broadcast(@Nonnull String leaves) throws IOException {
        return moment(leaves, null).getBroadcast();
    }

    @Nonnull
    private static FeedbackMomentAsset moment(@Nonnull String leaves, @Nonnull String id) throws IOException {
        String json = "{ \"Broadcast\": { \"Title\": { \"Key\": \"x.title\" }" + (leaves.isEmpty() ? "" : ", " + leaves)
                + " } }";
        return decode(json, id == null ? "Some_Moment" : id, null);
    }

    @Nonnull
    private static FeedbackMomentAsset decode(@Nonnull String json, @Nonnull String id,
            FeedbackMomentAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(FeedbackMomentAsset.class, id, null);
        return FeedbackMomentAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }

    // ==================== the leaves, read ====================

    @Test
    void anUnauthoredGroupIsUnscopedAndReachesEveryoneOnline() throws IOException {
        FeedbackMomentAsset.Broadcast spec = broadcast("");

        assertFalse(spec.isScoped(), "absent leaves are today's every-online fan-out, byte for byte");
        assertFalse(spec.toParticipants());
        assertFalse(spec.sameWorldOnly());
        assertNull(spec.getRadiusBlocks());
        assertEquals(0, spec.minSecondsBetween());
        assertTrue(BroadcastScope.admits(spec, false, false, UNKNOWN), "another world, unknown distance: still shown");
    }

    @Test
    void theFourLeavesDecodeAndCompose() throws IOException {
        FeedbackMomentAsset.Broadcast spec = broadcast(
                "\"ToParticipants\": true, \"SameWorldOnly\": true, \"RadiusBlocks\": 64, \"MinSecondsBetween\": 60");

        assertTrue(spec.isScoped());
        assertTrue(spec.toParticipants());
        assertTrue(spec.sameWorldOnly());
        assertEquals(64.0, spec.getRadiusBlocks());
        assertEquals(60, spec.minSecondsBetween());
    }

    @Test
    void aNegativeRadiusOrGapReadsAsUnauthored() throws IOException {
        FeedbackMomentAsset.Broadcast spec = broadcast("\"RadiusBlocks\": -5, \"MinSecondsBetween\": -1");

        assertNull(spec.getRadiusBlocks());
        assertEquals(0, spec.minSecondsBetween());
    }

    @Test
    void anOverlayKeepsTheLeavesItDidNotMention() throws IOException {
        FeedbackMomentAsset parent = moment("\"SameWorldOnly\": true, \"MinSecondsBetween\": 60", "Base");
        FeedbackMomentAsset child = decode("{ \"Broadcast\": { \"RadiusBlocks\": 32 } }", "Child", parent);

        FeedbackMomentAsset.Broadcast spec = child.getBroadcast();
        assertEquals(32.0, spec.getRadiusBlocks(), "the authored leaf landed");
        assertTrue(spec.sameWorldOnly(), "an unmentioned leaf is inherited, not reset");
        assertEquals(60, spec.minSecondsBetween(), "so is the throttle");
    }

    // ==================== who is shown ====================

    @Test
    void sameWorldOnlyKeepsTheBannerToTheSubjectsWorld() throws IOException {
        FeedbackMomentAsset.Broadcast spec = broadcast("\"SameWorldOnly\": true");

        assertTrue(BroadcastScope.admits(spec, false, true, UNKNOWN));
        assertFalse(BroadcastScope.admits(spec, false, false, UNKNOWN));
    }

    @Test
    void aRadiusKeepsTheBannerToPlayersNearTheSubject() throws IOException {
        FeedbackMomentAsset.Broadcast spec = broadcast("\"RadiusBlocks\": 64");

        assertTrue(BroadcastScope.admits(spec, false, true, NEAR));
        assertTrue(BroadcastScope.admits(spec, false, true, 64.0), "on the line is inside");
        assertFalse(BroadcastScope.admits(spec, false, true, FAR));
        assertFalse(BroadcastScope.admits(spec, false, true, UNKNOWN), "a position that cannot be read is outside");
        assertFalse(BroadcastScope.admits(spec, false, false, NEAR), "another world is outside any radius");
    }

    @Test
    void aParticipantIsShownWhereverTheyStandWhenAskedFor() throws IOException {
        FeedbackMomentAsset.Broadcast spec = broadcast("\"ToParticipants\": true, \"SameWorldOnly\": true, \"RadiusBlocks\": 16");

        assertTrue(BroadcastScope.admits(spec, true, false, UNKNOWN), "in another world, still told");
        assertFalse(BroadcastScope.admits(spec, false, true, FAR), "a bystander is still scoped");
        assertTrue(BroadcastScope.admits(spec, false, true, NEAR));
    }

    @Test
    void aZeroRadiusReachesNobodyButTheParticipants() throws IOException {
        FeedbackMomentAsset.Broadcast spec = broadcast("\"ToParticipants\": true, \"RadiusBlocks\": 0");

        assertTrue(BroadcastScope.admits(spec, true, false, UNKNOWN));
        assertFalse(BroadcastScope.admits(spec, false, true, NEAR));
        assertTrue(BroadcastScope.admits(spec, false, true, 0.0), "standing exactly on the subject is inside");
    }

    @Test
    void withoutToParticipantsAParticipantIsAViewerLikeAnyOther() throws IOException {
        FeedbackMomentAsset.Broadcast spec = broadcast("\"SameWorldOnly\": true");

        assertFalse(BroadcastScope.admits(spec, true, false, UNKNOWN));
    }

    // ==================== the throttle ====================

    @Test
    void theThrottleHoldsABannerBackPerKeyForTheAuthoredWindow() {
        BroadcastScope.Throttle throttle = new BroadcastScope.Throttle();

        assertTrue(throttle.allow("Engaged|Warden|world", 60, 1_000L));
        assertFalse(throttle.allow("Engaged|Warden|world", 60, 30_000L), "a second member's fire, same fight");
        assertTrue(throttle.allow("Engaged|Golem|world", 60, 30_000L), "another fight announces separately");
        assertTrue(throttle.allow("Engaged|Warden|other", 60, 30_000L), "another world hears its own");
        assertTrue(throttle.allow("Engaged|Warden|world", 60, 61_000L), "the window has passed");
    }

    @Test
    void noWindowNeverHoldsAnythingBackAndRecordsNothing() {
        BroadcastScope.Throttle throttle = new BroadcastScope.Throttle();

        assertTrue(throttle.allow("k", 0, 1L));
        assertTrue(throttle.allow("k", 0, 1L));
        assertEquals(0, throttle.size());
    }

    @Test
    void theThrottleSweepsExpiredKeysSoALongRunningServerKeepsOnlyWhatIsStillHeld() {
        BroadcastScope.Throttle throttle = new BroadcastScope.Throttle();
        for (int i = 0; i < 300; i++) {
            throttle.allow("moment|" + i, 10, 0L);
        }
        assertTrue(throttle.size() >= 256, "nothing has expired yet");

        throttle.allow("moment|late", 10, 20_000L);

        assertEquals(1, throttle.size(), "every window that had passed was swept");
    }

    @Test
    void theThrottleKeyIsTheMomentItsSourceAndTheWorldOnlyWhenWorldScoped() throws IOException {
        FeedbackEngine.Anchor nowhere = FeedbackEngine.Anchor.of(null);

        assertEquals("Encounter_Engaged|Warden|*",
                FeedbackEngine.throttleKey("Encounter_Engaged", broadcast("\"MinSecondsBetween\": 60"), nowhere,
                        java.util.Map.of(FeedbackEngine.SOURCE_ARG, "Warden")));
        assertEquals("Encounter_Engaged||",
                FeedbackEngine.throttleKey("Encounter_Engaged", broadcast("\"SameWorldOnly\": true"), nowhere,
                        java.util.Map.of()),
                "no source and no world anchor still yield a stable key");
    }
}
