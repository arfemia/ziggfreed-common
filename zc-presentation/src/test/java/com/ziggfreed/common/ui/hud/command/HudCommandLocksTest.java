package com.ziggfreed.common.ui.hud.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.ui.hud.panel.HudPanelAsset;
import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;

/** A verb that would change what the owner fixed refuses with its own line rather than reporting success. */
class HudCommandLocksTest {

    @Nonnull
    private static HudPanelAsset panel(@Nonnull String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudPanelAsset.class, "World_Bars", null);
        return HudPanelAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    @AfterEach
    void clear() {
        HudPanelConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void aFreeChoiceGoesAheadAndALockedOneRefuses() throws Exception {
        assertNull(HudCommandLocks.placeRefusal("World_Bars"));
        assertNull(HudCommandLocks.showRefusal("World_Bars"));

        HudPanelConfig.getInstance().mergePackLayer(Map.of("World_Bars",
                panel("{ \"Player\": { \"Show\": { \"Locked\": true }, \"Spot\": { \"Locked\": true } } }")));

        assertEquals("place.locked", HudCommandLocks.placeRefusal("World_Bars"));
        assertEquals("hide.locked", HudCommandLocks.showRefusal("world_bars"));
    }
}
