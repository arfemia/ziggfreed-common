package com.ziggfreed.common.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.protocol.EasingType;

/**
 * The camera sequence file's codec: every leaf reads as written, is written back and reads the same, and an empty
 * file or keyframe reads its documented defaults. Untagged: nothing here builds an engine item.
 */
class CameraSequenceAssetCodecTest {

    private static final String PAN = """
            {
              "LockInput": false,
              "HidePlayer": false,
              "Keyframes": [
                { "Position": { "X": 0.5, "Y": 20, "Z": 12 }, "LookAt": { "X": 0.5, "Y": 2, "Z": 50 },
                  "Seconds": 0.05, "Easing": "linear", "Fov": 80 },
                { "Position": { "Y": 14, "Z": 40 }, "Seconds": 3, "Easing": "SineOut" },
                { "ToPlayer": true, "Seconds": 1.5, "Easing": "NotAnEasing" }
              ]
            }
            """;

    @Test
    void everyLeafRoundTripsThroughTheCodec() throws IOException {
        CameraSequenceAsset read = decode("MyPack_Pan", PAN);
        assertPan(read);

        CameraSequenceAsset again = decode("MyPack_Pan", CameraSequenceAsset.CODEC.encode(read, new ExtraInfo()).toJson());
        assertPan(again);
        assertNotNull(CameraSequenceAsset.CODEC.toSchema(new SchemaContext()).getProperties().get("Keyframes"),
                "the schema the Asset Editor loads declares the keyframes");
    }

    @Test
    void anEmptyFileReadsEveryDefault() throws IOException {
        CameraSequenceAsset bare = decode("Bare", "{}");
        assertTrue(bare.lockInput(), "input is held unless a file says otherwise");
        assertTrue(bare.hidePlayer());
        assertTrue(bare.keyframes().isEmpty(), "no keyframes plays nothing");
        assertEquals(0f, bare.totalSeconds());

        CameraSequenceAsset.Keyframe k = decode("Bare", "{ \"Keyframes\": [ {} ] }").keyframes().get(0);
        assertEquals(CameraSequenceAsset.DEFAULT_SECONDS, k.seconds());
        assertEquals(CameraSequenceAsset.DEFAULT_EASING, k.easing());
        assertNull(k.position(), "no Position keeps the previous spot");
        assertNull(k.lookAt());
        assertNull(k.fov());
        assertFalse(k.toPlayer());
    }

    private static void assertPan(@Nonnull CameraSequenceAsset pan) {
        assertEquals("MyPack_Pan", pan.getId());
        assertFalse(pan.lockInput());
        assertFalse(pan.hidePlayer());
        List<CameraSequenceAsset.Keyframe> frames = pan.keyframes();
        assertEquals(3, frames.size());

        CameraSequenceAsset.Keyframe cut = frames.get(0);
        assertNotNull(cut.position());
        assertEquals(0.5, cut.position().effectiveX());
        assertEquals(20.0, cut.position().effectiveY());
        assertEquals(12.0, cut.position().effectiveZ());
        assertNotNull(cut.lookAt());
        assertEquals(50.0, cut.lookAt().effectiveZ());
        assertEquals(0.05f, cut.seconds());
        assertEquals(EasingType.Linear, cut.easing(), "an easing name matches without case");
        assertEquals(Float.valueOf(80f), cut.fov());

        CameraSequenceAsset.Keyframe sweep = frames.get(1);
        assertEquals(0.0, sweep.position().effectiveX(), "an unwritten axis is 0 from the origin");
        assertNull(sweep.lookAt(), "no LookAt keeps the previous look");
        assertEquals(EasingType.SineOut, sweep.easing());

        CameraSequenceAsset.Keyframe back = frames.get(2);
        assertTrue(back.toPlayer());
        assertEquals(CameraSequenceAsset.DEFAULT_EASING, back.easing(), "an unknown easing falls back, never fails");
        assertEquals(4.55f, pan.totalSeconds(), 1e-4f);
    }

    @Nonnull
    private static CameraSequenceAsset decode(@Nonnull String id, @Nonnull String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(CameraSequenceAsset.class, id, null);
        return CameraSequenceAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }
}
