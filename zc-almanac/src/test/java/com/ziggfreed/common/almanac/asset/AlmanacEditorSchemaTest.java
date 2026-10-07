package com.ziggfreed.common.almanac.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.ziggfreed.common.almanac.page.AlmanacDestinations;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The season page's schema loads in the in-game Asset Editor, where one bad schema breaks every type:
 * the whole page exports, the hero's item leaf offers the engine's own item picker, and ShowArt declares
 * its unauthored value.
 */
class AlmanacEditorSchemaTest {

    @AfterEach
    void clear() {
        Destinations.clearForTests();
    }

    @Test
    void theWholeSeasonPageExportsASchema() {
        AlmanacDestinations.register();

        Schema page = AlmanacEntryAsset.CODEC.toSchema(new SchemaContext());
        BsonDocument encoded = Schema.CODEC.encode(page, new ExtraInfo()).asDocument();

        BsonDocument properties = encoded.getDocument("properties");
        for (String leaf : new String[] {"Hero", "Accent", "Links"}) {
            assertTrue(properties.containsKey(leaf), "the page names its " + leaf + " leaf: " + properties.keySet());
        }
    }

    @Test
    void aHeroItemOffersTheEnginesItemPicker() {
        ObjectSchema placement = (ObjectSchema) AlmanacHeroAsset.Placement.CODEC.toSchema(new SchemaContext());
        BsonDocument item = Schema.CODEC.encode(placement, new ExtraInfo()).asDocument()
                .getDocument("properties").getDocument("Item");

        assertEquals("Item", item.getString("hytaleAssetRef").getValue());
    }

    @Test
    void showArtDeclaresItsUnauthoredValue() {
        ObjectSchema hero = (ObjectSchema) AlmanacHeroAsset.CODEC.toSchema(new SchemaContext());
        BsonDocument showArt = Schema.CODEC.encode(hero, new ExtraInfo()).asDocument()
                .getDocument("properties").getDocument("ShowArt");

        assertNotNull(showArt.get("default"), showArt.toJson());
        assertTrue(showArt.getBoolean("default").getValue());
    }
}
