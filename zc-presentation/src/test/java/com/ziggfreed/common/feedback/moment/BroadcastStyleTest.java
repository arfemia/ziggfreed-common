package com.ziggfreed.common.feedback.moment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;

/**
 * A banner's look and sound, over the codec that reads them: an authored {@code Style} wins over
 * {@code Major}, an unauthored or unknown one follows {@code Major}, a name is read whatever its case, an
 * overlay keeps the leaves it did not mention, and a blank sound is none.
 */
class BroadcastStyleTest {

    @Nonnull
    private static FeedbackMomentAsset.Broadcast broadcast(@Nonnull String leaves) throws IOException {
        return decode("{ \"Broadcast\": { \"Title\": { \"Key\": \"x.title\" }"
                + (leaves.isEmpty() ? "" : ", " + leaves) + " } }", "Some_Moment", null).getBroadcast();
    }

    @Nonnull
    private static FeedbackMomentAsset decode(@Nonnull String json, @Nonnull String id,
            @Nullable FeedbackMomentAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(FeedbackMomentAsset.class, id, null);
        return FeedbackMomentAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }

    @Test
    void anUnauthoredStyleFollowsTheMajorFlag() throws IOException {
        assertEquals(EventTitleStyle.Default, broadcast("").style());
        assertEquals(EventTitleStyle.Major, broadcast("\"Major\": true").style());
    }

    @Test
    void anAuthoredStyleWinsOverTheMajorFlag() throws IOException {
        assertEquals(EventTitleStyle.GoblinBreach, broadcast("\"Major\": true, \"Style\": \"GoblinBreach\"").style());
        assertEquals(EventTitleStyle.Default, broadcast("\"Major\": true, \"Style\": \"Default\"").style());
    }

    @Test
    void aStyleNameIsReadWhateverItsCase() throws IOException {
        assertEquals(EventTitleStyle.VoidEviction, broadcast("\"Style\": \" voideviction \"").style());
    }

    @Test
    void aStyleTheGameDoesNotHaveFollowsTheMajorFlag() throws IOException {
        assertEquals(EventTitleStyle.Major, broadcast("\"Major\": true, \"Style\": \"Enormous\"").style());
        assertEquals(EventTitleStyle.Default, broadcast("\"Style\": \"\"").style());
    }

    @Test
    void aSoundIsReadTrimmedAndABlankOneIsNone() throws IOException {
        assertEquals("SFX_Fanfare", broadcast("\"SoundEventId\": \" SFX_Fanfare \"").soundEventId());
        assertNull(broadcast("\"SoundEventId\": \"  \"").soundEventId());
        assertNull(broadcast("").soundEventId());
    }

    @Test
    void anOverlayKeepsTheLookAndSoundItDidNotMention() throws IOException {
        FeedbackMomentAsset parent = decode("{ \"Broadcast\": { \"Title\": { \"Key\": \"x.title\" }, "
                + "\"Style\": \"VoidEviction\", \"SoundEventId\": \"SFX_Fanfare\" } }", "Base", null);
        FeedbackMomentAsset child = decode("{ \"Broadcast\": { \"RadiusBlocks\": 32 } }", "Child", parent);

        assertEquals(EventTitleStyle.VoidEviction, child.getBroadcast().style());
        assertEquals("SFX_Fanfare", child.getBroadcast().soundEventId());
    }
}
