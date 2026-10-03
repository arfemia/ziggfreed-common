package com.ziggfreed.common.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;

/**
 * The one engine asset-store swap every module's tests share: each seed answers the LIVE read path
 * while it is open, and the slot holds what it held before once it closes.
 *
 * <p>Tagged {@code engine-items}: the engine's {@code AssetStore} constructor and its item classes
 * only load under the engine's own log manager, which only the {@code engineItemTest} task starts.
 */
@Tag("engine-items")
class EngineAssetStoresTest {

    /** A quality asset with only its id, built through the engine's public id constructor. */
    @Nonnull
    private static ItemQuality quality(@Nonnull String id) {
        return new ItemQuality(id);
    }

    @Test
    void anEmptyItemStoreLetsAStackBuildAndPutsTheOriginalBack() {
        Object before = Item.getAssetStore();
        try (EngineAssetStores.Swap ignored = EngineAssetStores.emptyItems()) {
            assertNotSame(before, Item.getAssetStore());
            ItemStack stack = new ItemStack("Test_Unknown_Item", 2);
            assertEquals(2, stack.getQuantity());
        }
        assertSame(before, Item.getAssetStore());
    }

    @Test
    void seededQualitiesResolveTheirIdsWithoutRegardToCase() {
        ItemQuality common = quality("Test_Fixture_Common");
        ItemQuality rare = quality("Test_Fixture_Rare");
        try (EngineAssetStores.Swap ignored = EngineAssetStores.qualities(common, rare)) {
            assertEquals(1, ItemQuality.getAssetMap().getIndex("test_fixture_RARE"));
            assertSame(rare, ItemQuality.getAssetMap().getAsset(1));
        }
    }

    @Test
    void seededStatChannelsResolveByPosition() {
        try (EngineAssetStores.Swap ignored = EngineAssetStores.statChannels(
                "Test_Fixture_Channel_A", "Test_Fixture_Channel_B")) {
            assertEquals(1, EntityStatType.getAssetMap().getIndex("Test_Fixture_Channel_B"));
        }
    }
}
