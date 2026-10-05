package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.commerce.asset.WhereAxis;
import com.ziggfreed.common.commerce.page.CommercePages;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;

/**
 * A storefront or a board with a {@code Where} exists only in the worlds it names: a player standing
 * anywhere else is not shown it, never lands on it as the unnamed default, and finds it closed when
 * something opens it by id. With no {@code Where} it is everywhere, as it always was, and the
 * viewer-less listing (the admin verbs, a server-wide question) still names everything.
 */
class StorefrontBoardWhereTest {

    /** The temple as a player inside it sees it: an instance name with a random suffix, its own config. */
    private static final LoadedWorld TEMPLE =
            new LoadedWorld("instance-Forgotten_Temple-3f1c2a9e", "ForgottenTemple");
    private static final LoadedWorld OVERWORLD = new LoadedWorld("default", "Default");
    private static final String WHERE_TEMPLE = "\"Where\": { \"GameplayConfig\": [ \"ForgottenTemple\" ] }";

    @BeforeEach
    void clear() {
        clearLayers();
    }

    @AfterEach
    void forget() {
        clearLayers();
    }

    private static void clearLayers() {
        ShopConfig.getInstance().mergePackLayer(Map.of());
        ShopConfig.getInstance().mergeOwnerLayer(Map.of());
        BoardConfig.getInstance().mergePackLayer(Map.of());
        BoardConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    // ==================== storefronts ====================

    @Test
    @DisplayName("a storefront with a Where is listed in its own world and left out everywhere else")
    void aStorefrontIsListedOnlyInItsOwnWorld() throws IOException {
        seedShops();

        assertEquals(List.of("general"), shopIds(ShopConfig.getInstance().listedIn(OVERWORLD)),
                "a player outside the temple is not shown the temple's stall");
        assertEquals(List.of("temple_stall", "general"), shopIds(ShopConfig.getInstance().listedIn(TEMPLE)),
                "a player inside it is, in the authored order");
        assertEquals(List.of("temple_stall", "general"), shopIds(ShopConfig.getInstance().listed()),
                "the viewer-less listing the admin verbs read still names every storefront");
    }

    @Test
    @DisplayName("the unnamed storefront default never lands on one outside the viewer's world")
    void theStorefrontDefaultSkipsOneOutsideItsWhere() throws IOException {
        seedShops();

        assertEquals("general", ShopConfig.getInstance().firstListedIdIn(OVERWORLD),
                "the stall sorts first, but it is not in this world");
        assertEquals("temple_stall", ShopConfig.getInstance().firstListedIdIn(TEMPLE));
        assertEquals("general", CommercePages.firstShopId(OVERWORLD),
                "the open seam's unnamed default reads the same list");
        assertEquals("temple_stall", CommercePages.firstShopId(TEMPLE));
        assertEquals("temple_stall", CommercePages.firstShopId(),
                "the no-world default is the old everywhere answer, so a caller not yet moved is unchanged");
    }

    @Test
    @DisplayName("a storefront opened by id is closed outside its Where and open inside it")
    void aStorefrontOpenedByIdIsClosedOutsideItsWhere() throws IOException {
        seedShops();
        StorefrontAsset stall = ShopConfig.getInstance().resolve("temple_stall");
        assertNotNull(stall, "the file stays loaded everywhere, so an open by id finds it");

        assertFalse(stall.isAvailableIn(OVERWORLD), "opened outside the temple, it reads closed");
        assertTrue(stall.isAvailableIn(TEMPLE));
        assertFalse(stall.existsIn(OVERWORLD));
        assertTrue(stall.existsIn(TEMPLE));
        assertTrue(stall.isAvailable(), "the world axis is its own: the owner's switch and features are untouched");
    }

    @Test
    @DisplayName("a storefront switched off is closed even in its own world")
    void aSwitchedOffStorefrontIsClosedInItsOwnWorldToo() throws IOException {
        ShopConfig.getInstance().mergePackLayer(Map.of("temple_stall", storefront("temple_stall",
                "{ \"Enabled\": false, " + WHERE_TEMPLE + " }")));
        StorefrontAsset stall = ShopConfig.getInstance().resolve("temple_stall");
        assertNotNull(stall);

        assertTrue(stall.existsIn(TEMPLE));
        assertFalse(stall.isAvailableIn(TEMPLE), "open needs both axes");
        assertTrue(ShopConfig.getInstance().listedIn(TEMPLE).isEmpty());
    }

    @Test
    @DisplayName("Where speaks the whole world grammar: a name pattern reaches the instance too")
    void aNamePatternWhereReachesTheInstance() throws IOException {
        ShopConfig.getInstance().mergePackLayer(Map.of("temple_stall", storefront("temple_stall",
                "{ \"Where\": { \"Match\": [ \"*Forgotten_Temple*\" ] } }")));
        StorefrontAsset stall = ShopConfig.getInstance().resolve("temple_stall");
        assertNotNull(stall);

        assertTrue(stall.isAvailableIn(TEMPLE));
        assertFalse(stall.isAvailableIn(OVERWORLD));
    }

    // ==================== boards ====================

    @Test
    @DisplayName("a board with a Where is listed in its own world and left out everywhere else")
    void aBoardIsListedOnlyInItsOwnWorld() throws IOException {
        seedBoards();

        assertEquals(List.of("daily"), boardIds(BoardConfig.getInstance().listedIn(OVERWORLD)));
        assertEquals(List.of("temple_daily", "daily"), boardIds(BoardConfig.getInstance().listedIn(TEMPLE)));
        assertEquals(List.of("daily"), specIds(CommerceCatalogs.boards().boardsIn(OVERWORLD)),
                "the catalogue a hub or a conversation reads gives the same answer");
        assertEquals(List.of("temple_daily", "daily"), specIds(CommerceCatalogs.boards().boardsIn(TEMPLE)));
        assertEquals(List.of("temple_daily", "daily"), specIds(CommerceCatalogs.boards().boards()),
                "the viewer-less catalogue the admin verbs read still names every board");
    }

    @Test
    @DisplayName("the unnamed board default never lands on one outside the viewer's world")
    void theBoardDefaultSkipsOneOutsideItsWhere() throws IOException {
        seedBoards();

        assertEquals("daily", BoardConfig.getInstance().firstListedIdIn(OVERWORLD));
        assertEquals("temple_daily", BoardConfig.getInstance().firstListedIdIn(TEMPLE));
        assertEquals("daily", CommercePages.firstBoardId(OVERWORLD));
        assertEquals("temple_daily", CommercePages.firstBoardId(TEMPLE));
        assertEquals("temple_daily", CommercePages.firstBoardId(),
                "the no-world default is the old everywhere answer");
    }

    @Test
    @DisplayName("a board opened by id is closed outside its Where and open inside it")
    void aBoardOpenedByIdIsClosedOutsideItsWhere() throws IOException {
        seedBoards();
        BoardAssetSpec spec = CommerceCatalogs.boards().board("temple_daily");
        assertNotNull(spec, "the file stays loaded everywhere, so an open by id finds it");

        assertFalse(spec.asset().isAvailableIn(OVERWORLD), "opened outside the temple, it reads closed");
        assertTrue(spec.asset().isAvailableIn(TEMPLE));
        assertFalse(spec.asset().existsIn(OVERWORLD));
        assertTrue(spec.enabled(), "the engine view has no viewer, so it reads everywhere as before");
    }

    // ==================== no Where ====================

    @Test
    @DisplayName("with no Where, a storefront or a board is everywhere, as before")
    void noWhereIsEverywhere() throws IOException {
        Map<String, StorefrontAsset> shops = new LinkedHashMap<>();
        shops.put("general", storefront("general", "{ \"Order\": 10 }"));
        shops.put("blank", storefront("blank", "{ \"Order\": 20, \"Where\": {} }"));
        ShopConfig.getInstance().mergePackLayer(shops);
        BoardConfig.getInstance().mergePackLayer(Map.of("daily", board("daily", "{ \"Order\": 10 }")));
        LoadedWorld unreadable = WhereAxis.viewer((World) null);

        for (LoadedWorld viewer : List.of(OVERWORLD, TEMPLE, unreadable)) {
            assertEquals(List.of("general", "blank"), shopIds(ShopConfig.getInstance().listedIn(viewer)),
                    "an unauthored or empty Where is every world");
            assertEquals("general", ShopConfig.getInstance().firstListedIdIn(viewer));
            assertEquals(List.of("daily"), boardIds(BoardConfig.getInstance().listedIn(viewer)));
            assertEquals("daily", BoardConfig.getInstance().firstListedIdIn(viewer));
            StorefrontAsset general = ShopConfig.getInstance().resolve("general");
            assertNotNull(general);
            assertTrue(general.isAvailableIn(viewer));
        }
    }

    @Test
    @DisplayName("a viewer whose world cannot be read sees only what exists everywhere")
    void anUnreadableWorldSeesOnlyTheEverywhereStock() throws IOException {
        seedShops();
        seedBoards();
        LoadedWorld unreadable = WhereAxis.viewer((World) null);

        assertEquals(List.of("general"), shopIds(ShopConfig.getInstance().listedIn(unreadable)));
        assertEquals(List.of("daily"), boardIds(BoardConfig.getInstance().listedIn(unreadable)));
    }

    // ==================== helpers ====================

    private static void seedShops() throws IOException {
        Map<String, StorefrontAsset> layer = new LinkedHashMap<>();
        layer.put("temple_stall", storefront("temple_stall", "{ \"Order\": 0, " + WHERE_TEMPLE + " }"));
        layer.put("general", storefront("general", "{ \"Order\": 10 }"));
        ShopConfig.getInstance().mergePackLayer(layer);
    }

    private static void seedBoards() throws IOException {
        Map<String, BoardAsset> layer = new LinkedHashMap<>();
        layer.put("temple_daily", board("temple_daily", "{ \"Order\": 0, " + WHERE_TEMPLE + " }"));
        layer.put("daily", board("daily", "{ \"Order\": 10 }"));
        BoardConfig.getInstance().mergePackLayer(layer);
    }

    private static StorefrontAsset storefront(String id, String json) throws IOException {
        return StorefrontAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(StorefrontAsset.class, id, null)));
    }

    private static BoardAsset board(String id, String json) throws IOException {
        return BoardAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(BoardAsset.class, id, null)));
    }

    private static List<String> shopIds(List<StorefrontAsset> shops) {
        List<String> out = new ArrayList<>();
        for (StorefrontAsset shop : shops) {
            out.add(shop.getId());
        }
        return out;
    }

    private static List<String> boardIds(List<BoardAsset> boards) {
        List<String> out = new ArrayList<>();
        for (BoardAsset board : boards) {
            out.add(board.getId());
        }
        return out;
    }

    private static List<String> specIds(List<BoardAssetSpec> specs) {
        List<String> out = new ArrayList<>();
        for (BoardAssetSpec spec : specs) {
            out.add(spec.boardId());
        }
        return out;
    }
}
