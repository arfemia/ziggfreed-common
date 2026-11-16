package com.ziggfreed.common.track.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.curve.CurveTable;

/**
 * A consumer's owner word on a track's {@code Cap} ({@link TrackConfig#ownerCap}) reaches every read of that Cap,
 * the track's ceiling included, keeping every other leaf; an entry in {@code tracks.json} for the same id keeps the
 * word unless it writes a Cap of its own, which wins; and with no word the track's own Cap stands, as on a server
 * running the library alone. Every number is the test's own.
 */
class TrackCapWordTest {

    private static final String TRACK = "Test_Cap_Word";
    private static final String OWNER = "test-cap-word";
    private static final CurveTable CURVE = CurveTable.build(List.of(CurveTable.Piece.hybrid(300, 200, 300)), 0L,
            List.of());

    @TempDir
    Path ownerDir;

    private Map<String, TrackAsset> packBefore;

    @BeforeEach
    void foldOneTrack() throws IOException {
        packBefore = TrackConfig.getInstance().packLayer();
        TrackAsset noParent = null;
        TrackAsset file = TrackAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(
                        "{ \"Curve\": \"Skill_Hybrid\", \"Cap\": 50, \"Order\": 3 }"), noParent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(TrackAsset.class, TRACK, null)));
        TrackConfig.getInstance().mergePackLayer(Map.of(TRACK, file));
        TrackOwnerLayers.setDirectory(ownerDir);
        TrackOwnerLayers.reload();
    }

    @AfterEach
    void putTheStoreBack() {
        TrackConfig.getInstance().ownerCap(OWNER, Map.of());
        TrackOwnerLayers.setDirectory(TrackOwnerLayers.DEFAULT_DIRECTORY);
        TrackConfig.getInstance().mergePackLayer(packBefore);
        TrackOwnerLayers.reload();
    }

    @Test
    void theWordIsTheCapEveryReadReadsAndKeepsTheRest() {
        assertEquals(Integer.valueOf(50), track().cap(), "no word: the track's own Cap stands");

        TrackConfig.getInstance().ownerCap(OWNER, Map.of(TRACK, 120));
        TrackAsset worded = track();
        assertEquals(Integer.valueOf(120), worded.cap(), "the word is the track's Cap");
        assertEquals(TRACK, worded.getId(), "the id as the file spells it");
        assertEquals(3, worded.order(), "every other leaf kept");
        TrackDef def = new TrackDef(worded, CURVE);
        assertEquals(CURVE.floorOf(120), def.ceiling(), "the ceiling a gain stops at reads it");
        assertEquals(120, def.capLevel());

        TrackConfig.getInstance().ownerCap(OWNER, Map.of());
        assertEquals(Integer.valueOf(50), track().cap(), "withdrawn, the track's own Cap stands again");
    }

    @Test
    void tracksJsonKeepsTheWordUnlessItWritesItsOwnCap() throws IOException {
        TrackConfig.getInstance().ownerCap(OWNER, Map.of(TRACK, 120));

        ownerFile("{ \"" + TRACK + "\": { \"Order\": 9 } }");
        assertEquals(9, track().order(), "the file's leaf is in force");
        assertEquals(Integer.valueOf(120), track().cap(), "and the word under it stands");
        assertNull(TrackConfig.getInstance().ownerFileCap(TRACK), "the file writes no Cap of its own");

        ownerFile("{ \"" + TRACK + "\": { \"Cap\": 70 } }");
        assertEquals(Integer.valueOf(70), track().cap(), "the file's own Cap wins over the word");
        assertEquals(Integer.valueOf(70), TrackConfig.getInstance().ownerFileCap(TRACK), "and says so");
    }

    private void ownerFile(@Nonnull String json) throws IOException {
        Files.writeString(ownerDir.resolve(TrackOwnerLayers.FILE), json, StandardCharsets.UTF_8);
        TrackOwnerLayers.reload();
    }

    @Nonnull
    private static TrackAsset track() {
        TrackAsset track = TrackConfig.getInstance().resolve(TRACK);
        assertNotNull(track);
        return track;
    }
}
