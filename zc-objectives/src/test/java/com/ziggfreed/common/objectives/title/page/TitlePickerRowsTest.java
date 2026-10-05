package com.ziggfreed.common.objectives.title.page;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.objectives.title.TitleAsset;
import com.ziggfreed.common.objectives.title.TitleConfig;
import com.ziggfreed.common.objectives.title.page.TitlePickerRows.Row;

/**
 * What the picker lists, worked out with no builder in hand: one row per unlocked title on offer, in
 * picker order, the shown one on; a switched-off or unknown title never listed; one note when there
 * is nothing to choose.
 */
class TitlePickerRowsTest {

    private static TitleAsset title(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TitleAsset.class, id, null);
        return TitleAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    @BeforeEach
    void titles() throws Exception {
        TitleConfig.getInstance().mergePackLayer(Map.of(
                "Hallows_Eve_Hallowed", title("{ \"Order\": 10 }", "Hallows_Eve_Hallowed"),
                "Pumpkin_King", title("{ \"Order\": 5 }", "Pumpkin_King"),
                "Off", title("{ \"Enabled\": false }", "Off")));
    }

    @AfterEach
    void clear() {
        TitleConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void eachOfferedUnlockedTitleIsOneRowInPickerOrderWithTheShownOneOn() {
        List<Row> rows = TitlePickerRows.plan(
                List.of("hallows_eve_hallowed", "pumpkin_king", "off", "never_defined"),
                "Hallows_Eve_Hallowed", TitleConfig.getInstance());

        assertEquals(List.of(Row.title("pumpkin_king", false), Row.title("hallows_eve_hallowed", true)), rows);
    }

    @Test
    void aShownTitleThatIsSwitchedOffLeavesEveryRowOff() {
        List<Row> rows = TitlePickerRows.plan(List.of("pumpkin_king", "off"), "off", TitleConfig.getInstance());

        assertEquals(List.of(Row.title("pumpkin_king", false)), rows);
    }

    @Test
    void nothingOfferedIsOneNoteSayingSo() {
        assertEquals(List.of(Row.note(TitlePickerRows.NONE_NOTE)),
                TitlePickerRows.plan(List.of(), null, TitleConfig.getInstance()));
        assertEquals(List.of(Row.note(TitlePickerRows.NONE_NOTE)),
                TitlePickerRows.plan(List.of("off", "never_defined"), "off", TitleConfig.getInstance()));
    }
}
