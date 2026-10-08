package com.ziggfreed.common.objectives.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.quest.asset.QuestIndicatorSpec;

/** The tracked pointer's look is the global word's alone, and an unwritten one is the marker service's default. */
class QuestIndicatorPointerTest {

    @AfterEach
    void clear() {
        QuestIndicatorConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void thePointerIconDecodesFromTheGlobalFile() throws IOException {
        QuestIndicatorAsset asset = QuestIndicatorAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromJsonString("{ \"Pointer\": { \"Icon\": \"Quest_Pin.png\" } }"),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(QuestIndicatorAsset.class, "Default", null)));
        assertEquals("Quest_Pin.png", asset.getPointer().getIcon());
    }

    @Test
    void thePointerIconIsTheGlobalWordsAndUnwrittenMeansTheServiceDefault() {
        assertNull(QuestIndicatorConfig.getInstance().pointerIcon(), "no file: the marker service's own icon");

        QuestIndicatorConfig.getInstance().mergePackLayer(Map.of(QuestIndicatorAsset.DEFAULT_ID,
                QuestIndicatorAsset.of(QuestIndicatorAsset.DEFAULT_ID, QuestIndicatorSpec.EMPTY,
                        QuestIndicatorAsset.Pointer.of(" Quest_Pin.png "))));
        assertEquals("Quest_Pin.png", QuestIndicatorConfig.getInstance().pointerIcon());
    }
}
