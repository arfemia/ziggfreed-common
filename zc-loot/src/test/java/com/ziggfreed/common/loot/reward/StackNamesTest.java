package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.testing.EngineAssetStores;

/**
 * An {@code Item} reward can name the stack it hands over (a keepsake carrying the year it was
 * earned), as the engine's own per-stack display name, a key each player's client resolves.
 */
@Tag("engine-items")
class StackNamesTest {

    @BeforeEach
    void emptyCatalogue() {
        LangCatalog.overrideForTests(Map.of());
    }

    @AfterEach
    void realCatalogue() {
        LangCatalog.overrideForTests(null);
    }

    @Test
    void aRewardNamingItsStackStampsTheNameOntoIt() {
        try (EngineAssetStores.Swap ignored = EngineAssetStores.emptyItems()) {
            RewardSpec spec = RewardSpec.of(LootRewardKinds.KIND_ITEM, Map.of("Item", "Fixture_Lantern",
                    "Count", "1", "StackNameKey", "fixture.keepsake.name", "StackNameArg", "2026"));

            ItemStack named = StackNames.stamp(new ItemStack("Fixture_Lantern", 1), spec);

            ItemDisplayMetadata display = named.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
            assertNotNull(display);
            assertEquals("fixture.keepsake.name", display.getName().getMessageId(),
                    "a key no catalogue ships stays as written; the player's client resolves it");
            assertEquals("fixture.keepsake.name", named.getDisplayName().getMessageId());
            FormattedMessage stored = display.getName().getFormattedMessage();
            assertNotNull(stored.messageParams, "the arg rides the stored name as a nested param");
            assertEquals("2026", stored.messageParams.get("0").rawText,
                    "the arg fills {0} as raw text, a label, never a grouped number");
        }
    }

    @Test
    void aRewardNamingNoStackLeavesItUntouched() {
        try (EngineAssetStores.Swap ignored = EngineAssetStores.emptyItems()) {
            ItemStack plain = new ItemStack("Fixture_Lantern", 1);

            assertSame(plain, StackNames.stamp(plain,
                    RewardSpec.of(LootRewardKinds.KIND_ITEM, Map.of("Item", "Fixture_Lantern"))));
        }
    }

    @Test
    void theItemKindDeclaresTheNameParameters() {
        assertTrue(LootRewardKinds.parameterKeys().get(LootRewardKinds.KIND_ITEM).contains("stacknamekey"));
        assertTrue(LootRewardKinds.parameterKeys().get(LootRewardKinds.KIND_ITEM).contains("stacknamearg"));
    }
}
