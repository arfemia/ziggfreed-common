package com.ziggfreed.common.objectives.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.feedback.moment.FeedbackMomentAsset;

/**
 * The shipped unlock notice must say something the player-facing file carries: a missing key is the
 * failure a notice cannot have, since nothing throws and the player reads the raw key.
 */
class TitleUnlockedMomentTest {

    private static final Path MOMENT = Path.of("src", "main", "resources", "Server", "ZiggfreedCommon",
            "FeedbackMoments", TitleText.UNLOCKED_MOMENT + ".json");

    @Test
    void theShippedUnlockMomentDrawsLinesThePlayerFacingFileCarries() throws IOException {
        assertTrue(Files.isRegularFile(MOMENT), "missing " + MOMENT.toAbsolutePath());
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(FeedbackMomentAsset.class,
                TitleText.UNLOCKED_MOMENT, null);
        FeedbackMomentAsset moment = FeedbackMomentAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(Files.readString(MOMENT, StandardCharsets.UTF_8)),
                null, new AssetExtraInfo<>(data));
        Map<String, String> english = TitleTextTest.shipped(TitleTextTest.ENGLISH);

        String title = moment.getToast().getTitle().getKey();
        assertTrue(title.startsWith(TitleText.PREFIX), "the toast line is the title family's own");
        assertTrue(english.containsKey(title.substring(TitleText.PREFIX.length())), "no English line for " + title);
        assertEquals(List.of(TitleText.NAME_ARG), List.of(moment.getToast().getTitle().getArgs()),
                "the one blank is the title's localized name, which the write passes under that name");

        String hint = moment.getToast().getSecondary().getKey();
        assertTrue(hint.startsWith(TitleText.PREFIX));
        assertTrue(english.containsKey(hint.substring(TitleText.PREFIX.length())), "no English line for " + hint);
    }
}
