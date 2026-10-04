package com.ziggfreed.common.almanac;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;

/** A season page shared by the module's tests, and the one way they decode a page. */
public final class AlmanacFixtures {

    /** One year-round line, one live-only line, a keepsake and a name. */
    public static final String SEASON_PAGE = """
            { "Text": { "TitleKey": "almanac.test.title", "FlavorKey": "almanac.test.flavor" },
              "Icon": "Test_Icon", "Order": 10, "Keepsake": "Test_Keepsake",
              "Stats": {
                "Bombs_Thrown": { "Kind": "USE_ITEM", "Target": "Test_Bomb_", "MatchMode": "PREFIX",
                                  "Qualifier": "Throw", "TextKey": "almanac.test.bombs", "Icon": "Test_Bomb",
                                  "Order": 10, "LiveOnly": false },
                "Ghouls": { "Kind": "KILL_ENTITY", "Target": "Test_Ghoul", "MatchMode": "EXACT", "Order": 20 } } }
            """;

    private AlmanacFixtures() {
    }

    @Nonnull
    public static AlmanacEntryAsset page(@Nonnull String json, @Nonnull String id) throws IOException {
        return page(json, id, null, null);
    }

    @Nonnull
    public static AlmanacEntryAsset page(@Nonnull String json, @Nonnull String id, @Nullable String parentId,
            @Nullable AlmanacEntryAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(AlmanacEntryAsset.class, id, parentId);
        return AlmanacEntryAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }
}
