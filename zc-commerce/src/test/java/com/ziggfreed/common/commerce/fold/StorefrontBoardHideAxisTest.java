package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.BoardEngine;
import com.ziggfreed.common.board.BoardQuests;
import com.ziggfreed.common.board.BountyRef;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.subject.Subject;

/**
 * A storefront or a board whose plain feature condition reads off is not on this server: it leaves
 * every listing and the unnamed default, and a board refuses every accept. Its lock is the rest of
 * its block. The namespace is unique to this class.
 */
class StorefrontBoardHideAxisTest {

    private static final String NAMESPACE = "hide_shopboard";
    private static final String FEATURE =
            "{ \"Factor\": \"hide_shopboard:feature\", \"Param\": \"Spooky\", \"Min\": 1 }";
    private static final Subject SUBJECT = Subject.of(UUID.randomUUID(), "Tester");
    private static final long NOW = 1_000_000_000_000L;

    private AtomicBoolean spooky;

    @BeforeEach
    void declareTheFeature() {
        spooky = new AtomicBoolean(true);
        FeatureFlags.register(NAMESPACE, "spooky", "test", spooky::get);
        clearLayers();
    }

    @AfterEach
    void forget() {
        clearLayers();
        FeatureFlags.reset();
    }

    private static void clearLayers() {
        ShopConfig.getInstance().mergePackLayer(Map.of());
        ShopConfig.getInstance().mergeOwnerLayer(Map.of());
        BoardConfig.getInstance().mergePackLayer(Map.of());
        BoardConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    // ==================== storefronts ====================

    @Test
    @DisplayName("a storefront whose feature is off leaves every listing and is never the default")
    void aHiddenStorefrontLeavesTheListAndTheDefault() throws IOException {
        Map<String, StorefrontAsset> layer = new LinkedHashMap<>();
        layer.put("spooky_stall", storefront("spooky_stall",
                "{ \"Order\": 0, \"Requires\": { \"Factors\": [ " + FEATURE + " ] } }"));
        layer.put("general", storefront("general", "{ \"Order\": 10 }"));
        ShopConfig.getInstance().mergePackLayer(layer);

        assertEquals(List.of("spooky_stall", "general"), listedShopIds());
        assertEquals("spooky_stall", ShopConfig.getInstance().firstListedId());

        spooky.set(false);

        assertEquals(List.of("general"), listedShopIds(), "off means absent, read live");
        assertEquals("general", ShopConfig.getInstance().firstListedId(),
                "an unnamed shop destination never opens a storefront that is not there");
        StorefrontAsset stall = ShopConfig.getInstance().resolve("spooky_stall");
        assertNotNull(stall, "the file stays loaded, so the storefront comes back with the feature");
        assertTrue(stall.isEnabled(), "Enabled is the owner's switch; the feature is a second axis");
        assertFalse(stall.isAvailable());
    }

    @Test
    @DisplayName("a storefront switched off stays off whatever its feature says")
    void enabledFalseWinsForAStorefront() throws IOException {
        ShopConfig.getInstance().mergePackLayer(Map.of("spooky_stall", storefront("spooky_stall",
                "{ \"Enabled\": false, \"Requires\": { \"Factors\": [ " + FEATURE + " ] } }")));

        StorefrontAsset stall = ShopConfig.getInstance().resolve("spooky_stall");
        assertNotNull(stall);
        assertFalse(stall.isAvailable());
        assertNull(ShopConfig.getInstance().firstListedId(), "a server listing nothing declines");
    }

    @Test
    @DisplayName("a storefront's lock is its Requires block with the feature taken out")
    void aStorefrontsLockLeavesTheFeatureOut() throws IOException {
        StorefrontAsset stall = storefront("spooky_stall",
                "{ \"Requires\": { \"Factors\": [ " + FEATURE + " ], \"Permission\": \"shop.vip\" } }");

        GateSpec lock = stall.lockRequires();

        assertNotNull(lock);
        assertEquals(0, lock.factorsOrEmpty().length, "the feature decides presence, never a lock");
        assertEquals("shop.vip", lock.getPermission());
        assertNull(storefront("general", "{}").lockRequires(), "an unauthored block locks nothing");
    }

    // ==================== boards ====================

    @Test
    @DisplayName("a board whose feature is off leaves every listing, is never the default and refuses accept")
    void aHiddenBoardLeavesTheListAndRefusesAccept() throws IOException {
        Map<String, BoardAsset> layer = new LinkedHashMap<>();
        layer.put("spooky_daily", board("spooky_daily", "{ \"Order\": 0,"
                + " \"Rotation\": { \"Period\": \"Daily\" }, \"Slots\": [ { \"Count\": 1 } ],"
                + " \"Requires\": { \"Factors\": [ " + FEATURE + " ] } }"));
        layer.put("daily", board("daily", "{ \"Order\": 10 }"));
        BoardConfig.getInstance().mergePackLayer(layer);
        BoardAssetSpec spec = CommerceCatalogs.boards().board("spooky_daily");
        assertNotNull(spec);
        BountyRef contract = contractOn("spooky_daily");

        assertTrue(spec.enabled());
        assertTrue(engine().canAccept(SUBJECT, spec, contract, NOW).ok(),
                "while it is on, the feature is no lock of its own");
        assertEquals("spooky_daily", BoardConfig.getInstance().firstListedId());

        spooky.set(false);

        assertEquals(List.of("daily"), listedBoardIds());
        assertEquals(List.of("daily"), catalogBoardIds(), "every surface listing boards reads the same list");
        assertEquals("daily", BoardConfig.getInstance().firstListedId());
        assertFalse(spec.enabled(), "the same view reads off on the next look");
        BoardEngine.BoardCheck check = engine().canAccept(SUBJECT, spec, contract, NOW);
        assertFalse(check.ok());
        assertEquals(BoardEngine.REASON_DISABLED, check.reason());
    }

    @Test
    @DisplayName("a board's accept gate is its lock only")
    void aBoardsAcceptGateIsItsLock() throws IOException {
        BoardConfig.getInstance().mergePackLayer(Map.of("spooky_daily", board("spooky_daily",
                "{ \"Requires\": { \"Factors\": [ " + FEATURE + " ], \"Permission\": \"board.vip\" } }")));
        BoardAssetSpec spec = CommerceCatalogs.boards().board("spooky_daily");
        assertNotNull(spec);

        GateSpec gate = spec.requires();

        assertNotNull(gate);
        assertEquals(0, gate.factorsOrEmpty().length);
        assertEquals("board.vip", gate.getPermission());
    }

    // ==================== helpers ====================

    private static StorefrontAsset storefront(String id, String json) throws IOException {
        return StorefrontAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(StorefrontAsset.class, id, null)));
    }

    private static BoardAsset board(String id, String json) throws IOException {
        return BoardAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(BoardAsset.class, id, null)));
    }

    private static List<String> listedShopIds() {
        List<String> out = new ArrayList<>();
        for (StorefrontAsset shop : ShopConfig.getInstance().listed()) {
            out.add(shop.getId());
        }
        return out;
    }

    private static List<String> listedBoardIds() {
        List<String> out = new ArrayList<>();
        for (BoardAsset board : BoardConfig.getInstance().listed()) {
            out.add(board.getId());
        }
        return out;
    }

    private static List<String> catalogBoardIds() {
        List<String> out = new ArrayList<>();
        for (BoardAssetSpec spec : CommerceCatalogs.boards().boards()) {
            out.add(spec.boardId());
        }
        return out;
    }

    private static BoardEngine engine() {
        return BoardEngine.builder(BoardQuests.NONE, GateEvaluator.builder().warn(msg -> { }).build())
                .store(new InMemoryCommerceStore())
                .warn(msg -> { })
                .build();
    }

    /** A contract that names one board and nothing else; everything else about it is a quest's. */
    private static BountyRef contractOn(String boardId) {
        return new BountyRef() {
            @Override
            @Nonnull
            public String bountyId() {
                return "spooky_hunt";
            }

            @Override
            public boolean isOn(@Nonnull String board) {
                return boardId.equalsIgnoreCase(board);
            }

            @Override
            @Nullable
            public String difficultyOn(@Nonnull String board) {
                return null;
            }
        };
    }
}
