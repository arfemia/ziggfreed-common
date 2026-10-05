package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.StorefrontAsset;

/**
 * Every leaf that names an item id offers the engine's own item picker in the Asset Editor (the schema's
 * {@code hytaleAssetRef} naming the item type), never an editor pick list nothing serves, and a
 * hand-written id the picker never offered still loads as written.
 */
class ItemPickerSchemaTest {

    /** One item-id leaf: what it is, the object schema it sits in, and its key there. */
    private record Leaf(@Nonnull String what, @Nonnull ObjectSchema owner, @Nonnull String key) {

        @Nonnull
        Schema schema() {
            Schema leaf = owner.getProperties().get(key);
            assertNotNull(leaf, what + " has no " + key + " leaf");
            return leaf;
        }
    }

    @Nonnull
    private static List<Leaf> leaves() {
        return List.of(
                new Leaf("a storefront's icon", StorefrontAsset.CODEC.toSchema(new SchemaContext()), "Icon"),
                new Leaf("a board's icon", BoardAsset.CODEC.toSchema(new SchemaContext()), "Icon"),
                new Leaf("an offer's icon", ShopEntryAsset.CODEC.toSchema(new SchemaContext()), "Icon"),
                new Leaf("a wallet's icon", CurrencyAsset.CODEC.toSchema(new SchemaContext()), "Icon"),
                new Leaf("a wallet's backing item", CurrencyAsset.Backing.CODEC.toSchema(new SchemaContext()),
                        "Item"),
                new Leaf("a price's item", CostAsset.ItemCostAsset.CODEC.toSchema(new SchemaContext()), "Item"),
                new Leaf("a calendar event's icon",
                        CalendarEventAsset.Presentation.CODEC.toSchema(new SchemaContext()), "Icon"));
    }

    @Test
    void everyItemLeafOffersTheEnginesItemPickerAsTheEditorReceivesIt() {
        for (Leaf leaf : leaves()) {
            BsonDocument encoded = Schema.CODEC.encode(leaf.owner(), new ExtraInfo()).asDocument();
            BsonDocument property = encoded.getDocument("properties").getDocument(leaf.key());
            assertTrue(property.containsKey("hytaleAssetRef"),
                    leaf.what() + " names no asset type: " + property.toJson());
            assertEquals("Item", property.getString("hytaleAssetRef").getValue(), leaf.what());
        }
    }

    @Test
    void noItemLeafStillNamesTheListNobodyServes() {
        for (Leaf leaf : leaves()) {
            Schema.HytaleMetadata hytale = leaf.schema().getHytale();
            assertTrue(hytale == null || hytale.getUiEditorComponent() == null,
                    leaf.what() + " still names an editor pick list");
        }
    }

    @Test
    void aHandWrittenItemIdThePickerNeverOfferedStillLoads() throws IOException {
        CostAsset.ItemCostAsset price = CostAsset.ItemCostAsset.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{ \"Item\": \"A_Later_Packs_Item\" }"), new ExtraInfo());
        assertEquals("A_Later_Packs_Item", price.getItem());

        CurrencyAsset.Backing backing = CurrencyAsset.Backing.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{ \"Item\": \"coin_gold\" }"), new ExtraInfo());
        assertEquals("coin_gold", backing.getItem(), "an id in another casing is kept as written");
    }
}
