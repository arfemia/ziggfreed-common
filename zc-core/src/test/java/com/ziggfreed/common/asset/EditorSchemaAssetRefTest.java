package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;

/**
 * The engine's own asset picker, written as a schema fact and nothing else: a string leaf, or
 * each entry of a string-array leaf, names the asset type the Asset Editor knows by the class's
 * simple name; a leaf of another shape is left alone; and decoding refuses nothing, so an id in
 * any casing, or one a later pack ships, still loads.
 */
class EditorSchemaAssetRefTest {

    /** One leaf of each shape the hint can meet. */
    private static final class Probe {
        String one;
        String[] many;
        Integer count;
    }

    private static final BuilderCodec<Probe> CODEC = BuilderCodec.builder(Probe.class, Probe::new)
            .appendInherited(new KeyedCodec<>("One", Codec.STRING, false),
                    (o, v) -> o.one = v, o -> o.one, (o, p) -> o.one = p.one)
            .metadata(EditorSchema.assetRef(ItemQuality.class)).add()
            .appendInherited(new KeyedCodec<>("Many", Codec.STRING_ARRAY, false),
                    (o, v) -> o.many = v, o -> o.many, (o, p) -> o.many = p.many)
            .metadata(EditorSchema.assetRef(ItemQuality.class)).add()
            .appendInherited(new KeyedCodec<>("Count", Codec.INTEGER, false),
                    (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
            .metadata(EditorSchema.assetRef(ItemQuality.class)).add()
            .build();

    private static Map<String, Schema> leaves() {
        return CODEC.toSchema(new SchemaContext()).getProperties();
    }

    @Test
    void aStringLeafNamesTheAssetTypeByItsClassName() {
        // The Asset Editor knows a type by its asset class's simple name: the name the engine's
        // own AssetKeyValidator writes and the editor's AssetStoreTypeHandler registers.
        assertEquals("ItemQuality", leaves().get("One").getHytaleAssetRef());
    }

    @Test
    void aStringArrayLeafNamesItOnEachEntryAndNotOnTheList() {
        ArraySchema many = (ArraySchema) leaves().get("Many");
        assertEquals("ItemQuality", ((StringSchema) many.getItems()).getHytaleAssetRef(),
                "each entry is one reference, so each entry offers the picker");
        assertNull(many.getHytaleAssetRef(), "the list itself is not a reference");
    }

    @Test
    void aLeafOfAnotherShapeIsLeftAlone() {
        assertNull(leaves().get("Count").getHytaleAssetRef());
    }

    @Test
    void decodingRefusesNothingAndKeepsEveryId() throws IOException {
        // ExtraInfo() reports through the engine's throwing results: a failed check throws here.
        Probe probe = CODEC.decodeJson(RawJsonReader.fromJsonString(
                "{ \"One\": \"No_Such_Quality\", \"Many\": [\"rare\", \"A_Later_Packs_Tier\"] }"),
                new ExtraInfo());

        assertEquals("No_Such_Quality", probe.one);
        assertArrayEquals(new String[] {"rare", "A_Later_Packs_Tier"}, probe.many);
    }
}
