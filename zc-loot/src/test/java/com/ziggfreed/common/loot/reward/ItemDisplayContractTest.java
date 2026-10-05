package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.testing.EngineAssetStores;

/**
 * Recon gate G2, kept as a contract: the engine this library compiles against lets ONE stack carry
 * a display name of its own, written into the stack's metadata under the engine's own key and read
 * back by the stack's display-name accessor. A reward that names the stack it hands over rides this,
 * so an engine that dropped the surface fails here rather than at a player's tooltip.
 *
 * <p>Tagged {@code engine-items}: an {@link ItemStack} only loads under the engine's log manager, and
 * its constructor asks the item store, which is swapped for an empty one for the test's length.
 */
@Tag("engine-items")
class ItemDisplayContractTest {

    @Test
    void aStackCarriesADisplayNameOfItsOwn() {
        try (EngineAssetStores.Swap ignored = EngineAssetStores.emptyItems()) {
            ItemStack plain = new ItemStack("Fixture_Lantern", 1);
            ItemStack named = plain.withMetadata(ItemDisplayMetadata.KEYED_CODEC,
                    new ItemDisplayMetadata(Message.raw("Fixture Lantern"), null));

            ItemDisplayMetadata display = named.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
            assertNotNull(display, "the name survives the round trip through the stack's metadata");
            assertEquals("Fixture Lantern", display.getName().getRawText());
            assertEquals("Fixture Lantern", named.getDisplayName().getRawText(),
                    "the stack's own name is what its display-name accessor answers");
            assertNull(plain.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC),
                    "a stack nobody named carries no display metadata");
        }
    }
}
