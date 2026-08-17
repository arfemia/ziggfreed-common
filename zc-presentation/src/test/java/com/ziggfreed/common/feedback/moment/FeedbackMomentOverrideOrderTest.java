package com.ziggfreed.common.feedback.moment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.AssetMergeAdapter;

/**
 * A consumer's same-id moment file WINS over the library's shipped default, and it does so by PACK
 * ORDER through the exact path the fold takes at runtime.
 *
 * <p>The library's defaults are ordinary files in its own jar's asset pack, so they and a
 * consumer's files land in the ONE engine asset map, where a later-loaded pack's entry replaces an
 * earlier one's under the same id (the engine keeps a per-id chain of packs and answers with the
 * last). Packs load in manifest dependency order, so a consumer that lists this library as a
 * dependency loads after it. This test drives that same map with two packs in that order and folds
 * it the way {@code FrameworkAssetRegistrar} does, so the whole runtime path is what is pinned, not
 * a re-statement of it.
 */
class FeedbackMomentOverrideOrderTest {

    private static final String LIBRARY_PACK = "Ziggfreed:ZiggfreedCommon";
    private static final String CONSUMER_PACK = "Ziggfreed:SomeConsumer";

    @BeforeEach
    @AfterEach
    void clear() {
        FeedbackMomentConfig.getInstance().mergePackLayer(Map.of());
    }

    /** The engine map with its pack-loading door opened for a test. */
    private static final class TwoPackMap extends DefaultAssetMap<String, FeedbackMomentAsset> {

        void load(@Nonnull String pack, @Nonnull String id, @Nonnull FeedbackMomentAsset asset) {
            putAll(pack, FeedbackMomentAsset.CODEC, Map.of(id, asset),
                    Map.of(id, Path.of(pack.replace(':', '_'), id + ".json")), Map.of(id, Set.of()));
        }
    }

    @Nonnull
    private static FeedbackMomentAsset moment(@Nonnull String id, @Nonnull String soundId)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(FeedbackMomentAsset.class, id, null);
        return FeedbackMomentAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString("{ \"Sound\": { \"Id\": \"" + soundId + "\" } }"),
                null, new AssetExtraInfo<>(data));
    }

    @Test
    void aConsumersSameIdFileReplacesTheLibrarysDefault() throws IOException {
        TwoPackMap map = new TwoPackMap();
        map.load(LIBRARY_PACK, "quest.completed", moment("quest.completed", "SFX_Library"));
        map.load(LIBRARY_PACK, "quest.parked", moment("quest.parked", "SFX_Library_Parked"));
        map.load(CONSUMER_PACK, "quest.completed", moment("quest.completed", "SFX_Consumer"));

        FeedbackMomentConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(map));

        FeedbackMomentAsset completed = FeedbackMomentConfig.getInstance().resolve("quest.completed");
        assertNotNull(completed);
        assertEquals("SFX_Consumer", completed.getSound().getId(),
                "the later-loaded consumer pack's file answers for the shared id");
        assertEquals(CONSUMER_PACK, map.getAssetPack("quest.completed"),
                "and the engine map itself attributes the id to that pack");

        FeedbackMomentAsset parked = FeedbackMomentConfig.getInstance().resolve("quest.parked");
        assertNotNull(parked);
        assertEquals("SFX_Library_Parked", parked.getSound().getId(),
                "a moment the consumer left alone keeps the library's default");
    }
}
