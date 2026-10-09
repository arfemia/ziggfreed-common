package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.ModGateFold;
import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.shop.asset.ShopAssetStore;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.validation.Finding;

/**
 * The whole commerce audit, as the boot pass and {@code /zigcommerce validate} run it, on a server without a
 * companion mod: a wallet, a storefront and a board the mod gate refused (a gated pack file, or an owner entry
 * that took its whole id out) are named by no line, though ungated content still prices in that wallet, sells
 * at that storefront, includes it and posts to that board; a wallet nothing refused still warns. The
 * refusals are seeded on the stores as their load handlers leave them, and cleared after every test.
 */
class CommerceAuditModGateTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String PIE = "\"Rewards\": [ { \"Kind\": \"Item\", \"Params\": { \"Item\": \"Ore_Iron\" } } ]";

    @BeforeEach
    @AfterEach
    void clearStores() {
        ShopAssetStore.getInstance().mergeEntries(Map.of());
        ShopAssetStore.getInstance().mergeGenerators(Map.of());
        ShopConfig.getInstance().mergePackLayer(Map.of());
        ShopConfig.getInstance().mergeOwnerLayer(Map.of());
        BoardConfig.getInstance().mergePackLayer(Map.of());
        BoardConfig.getInstance().mergeOwnerLayer(Map.of());
        BoardAssetStore.getInstance().merge(Map.of());
        CurrencyConfig.getInstance().mergePackLayer(Map.of());
        CurrencyConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    private static <T extends JsonAsset<String>> T decode(AssetBuilderCodec<String, T> codec, Class<T> type,
            String id, String json) throws IOException {
        return codec.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(type, id, null)));
    }

    @Test
    void theAuditNamesNoIdTheModGateRefusedAndStillNamesAWalletNothingDefines() throws IOException {
        CurrencyConfig.getInstance().mergePackLayer(new ModGateFold<>(CurrencyConfig.MOD_GATE_STORE,
                Map.of("bounty_token", decode(CurrencyAsset.CODEC, CurrencyAsset.class, "bounty_token",
                        "{ \"Icon\": \"Ingredient_Bar_Gold\" }")),
                Map.of("test_gated_coin", MMO)));
        ShopConfig.getInstance().mergePackLayer(new ModGateFold<>(ShopConfig.MOD_GATE_STORE,
                Map.of("test_host", decode(StorefrontAsset.CODEC, StorefrontAsset.class, "test_host",
                        "{ \"Currencies\": [\"Bounty_Token\", \"Test_Gated_Coin\"],"
                                + " \"Includes\": [\"Test_Gated_Stall\"] }")),
                Map.of("test_gated_stall", MMO)));
        BoardConfig.getInstance().mergeOwnerLayer(Map.of(), Map.of("test_gated_board", MMO));
        ShopAssetStore.getInstance().mergeEntries(Map.of(
                "test_stall_pie", decode(ShopEntryAsset.CODEC, ShopEntryAsset.class, "test_stall_pie",
                        "{ \"Shop\": \"Test_Gated_Stall\","
                                + " \"Cost\": { \"Currencies\": { \"Test_Gated_Coin\": 5 } }, " + PIE + " }"),
                "test_host_tart", decode(ShopEntryAsset.CODEC, ShopEntryAsset.class, "test_host_tart",
                        "{ \"Shop\": \"Test_Host\","
                                + " \"Cost\": { \"Currencies\": { \"Dragon_Scale\": 5 } }, " + PIE + " }")));
        BoardAssetStore.getInstance().merge(Map.of("test_job", decode(BountyAsset.CODEC, BountyAsset.class,
                "test_job", "{ \"Boards\": [ { \"Board\": \"Test_Gated_Board\" } ],"
                        + " \"Objectives\": { \"main\": { \"Kind\": \"KILL_ENTITY\", \"Amount\": 1 } },"
                        + " \"Rewards\": { \"Claim\": [ { \"Kind\": \"Item\", \"Params\": { \"Item\": \"Ore_Iron\" } } ] } }")));

        List<Finding> findings = CommerceAudit.auditAll();

        for (Finding finding : findings) {
            assertFalse((finding.message() + " " + finding.sourceId()).toLowerCase(Locale.ROOT).contains("test_gated"),
                    "no line names an id the mod gate refused: " + finding);
        }
        assertTrue(findings.stream().anyMatch(f -> "UNKNOWN_CURRENCY".equals(f.code())
                        && f.message().contains("Dragon_Scale")),
                "a wallet nothing defines and nothing refused is still a finding: " + findings);
    }
}
