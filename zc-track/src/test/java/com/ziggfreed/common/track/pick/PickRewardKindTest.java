package com.ziggfreed.common.track.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * The Pick reward kind's two seams and the pick it gives: its params are spelt in one place and read back as written,
 * the console line a Claim keeps beside it is an admin's teach ({@code /zigtrack pick --pick}, which the command
 * answers by the same rules), and a pick written {@code TaughtOnly} reads so and stays in use through every build swap.
 * A pick with no {@code Stance} is a face of nothing and in no stance.
 */
class PickRewardKindTest {

    @Test
    void itsParamsAndItsRetryLineAreSpeltOnce() {
        RewardSpec spec = PickRewardKind.spec("Your_Track", "Your_Key");
        assertTrue(PickRewardKind.KIND.equalsIgnoreCase(spec.kind()));
        assertEquals("Your_Track", PickRewardKind.trackOf(spec));
        assertEquals("Your_Key", PickRewardKind.pickOf(spec));
        assertEquals("zigtrack pick --player=Someone --track=your_track --pick=Your_Key",
                PickRewardKind.line("your_track", "Your_Key", "Someone"),
                "the teach /zigtrack pick answers: a pick named and no option");
    }

    @Test
    void aTaughtOnlyPickStaysInUseAndAPlainOneIsInNoStance() throws IOException {
        TrackPickAsset key = decode("Your_Key", "{ \"Track\": \"Your_Track\", \"TaughtOnly\": true }");
        TrackPickAsset plain = decode("Your_Plain", "{ \"Track\": \"Your_Track\", \"Cost\": { \"Points\": 1 } }");
        assertTrue(key.taughtOnly());
        assertTrue(PickService.staysInUse(key), "a swap never re-spends or saves what only a teach gives");
        assertFalse(plain.taughtOnly());
        assertFalse(PickService.staysInUse(plain), "a tree's own pick swaps as it always did");
        assertNull(plain.stanceId());
        assertNull(plain.stanceFace());
        assertFalse(plain.isStanceFace());

        TrackPickAsset step = decode("Your_Step", "{ \"Track\": \"Your_Track\", \"Stance\": { \"Id\": \"Your_Stance\","
                + " \"Face\": \"Your_Face\", \"Kind\": \"Ignored\" } }");
        assertEquals("your_stance", step.stanceId());
        assertEquals("your_face", step.stanceFace(), "a step lives with the face it names");
        assertFalse(step.isStanceFace());
        assertNull(step.stanceKind(), "a step reads its face's kind, never its own");
        TrackPickAsset face = decode("Your_Face", "{ \"Track\": \"Your_Track\", \"Stance\": { \"Id\": \"Your_Stance\","
                + " \"Kind\": \"Weapon\" } }");
        assertTrue(face.isStanceFace(), "a pick naming no Face is a face itself");
        assertEquals("weapon", face.stanceKind());
    }

    private static TrackPickAsset decode(String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TrackPickAsset.class, id, null);
        return TrackPickAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
    }
}
