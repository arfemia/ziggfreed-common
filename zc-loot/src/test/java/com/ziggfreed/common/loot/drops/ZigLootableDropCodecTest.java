package com.ziggfreed.common.loot.drops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.asset.type.item.config.container.ItemDropContainer;

/**
 * A drop list's container written {@code "Type": "ZigLootable"} decodes, through the engine's own drop container
 * codec, to our container naming its table with the engine's own {@code Weight}, its {@code Lootable} leaf the Asset
 * Editor's own table picker; one naming no table still decodes.
 *
 * <p>Both are tagged {@code engine-items}: the engine's drop containers build a logger as their codecs initialize,
 * which needs the engine's log manager.
 */
class ZigLootableDropCodecTest {

    /** As the bootstrap registers the Type through the plugin's codec registry, which a unit JVM has no plugin for. */
    private static synchronized void registerTheType() {
        if (ItemDropContainer.CODEC.getCodecFor(ZigLootableDrop.TYPE) == null) {
            ItemDropContainer.CODEC.register(ZigLootableDrop.TYPE, ZigLootableDrop.class, ZigLootableDrop.CODEC);
        }
    }

    private static ItemDropContainer decode(String json) throws IOException {
        registerTheType();
        return ItemDropContainer.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    @Tag("engine-items")
    void theTypeDecodesThroughTheEnginesContainerCodec() throws IOException {
        ItemDropContainer read = decode("{ \"Type\": \"ZigLootable\", \"Weight\": 40, \"Lootable\": \" Trork_Spoils \" }");
        ZigLootableDrop container = assertInstanceOf(ZigLootableDrop.class, read);
        assertEquals("Trork_Spoils", container.tableId(), "the table it names, trimmed");
        assertEquals(40.0, container.getWeight(), "the engine's own Weight reads as every container's does");
        assertEquals("LootableAsset",
                ZigLootableDrop.CODEC.toSchema(new SchemaContext()).getProperties().get("Lootable").getHytaleAssetRef(),
                "the schema the Asset Editor loads offers its own picker over the loaded tables");
    }

    @Test
    @Tag("engine-items")
    void aContainerNamingNoTableStillDecodesAndNamesNone() throws IOException {
        ZigLootableDrop container = assertInstanceOf(ZigLootableDrop.class, decode("{ \"Type\": \"ZigLootable\" }"));
        assertNull(container.tableId(), "an unwritten Lootable never fails the drop list; the audit warns of it");
    }
}
