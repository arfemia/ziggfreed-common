package com.ziggfreed.common.loot.stamp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import javax.annotation.Nonnull;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.ziggfreed.common.asset.EditorDataSets;

/**
 * The library's own item-quality leaves offer the engine's quality picker in the Asset Editor: a
 * roll pool's {@code Quality} and a stamp's, whichever factor pick list the stamp codec was built
 * with, read back from the schema the way the editor receives it.
 */
class StampQualityEditorSchemaTest {

    @Test
    void aRollPoolsQualityOffersTheQualityPicker() {
        assertEquals("ItemQuality", encodedQualityRef(RollPoolAsset.CODEC.toSchema(new SchemaContext())));
    }

    @Test
    void aStampsQualityOffersTheQualityPickerInEveryBuild() {
        for (BuilderCodec<StampSpec> codec : List.of(StampSpec.CODEC, StampSpec.codec(EditorDataSets.FACTORS))) {
            assertEquals("ItemQuality", encodedQualityRef(codec.toSchema(new SchemaContext())));
        }
    }

    /** The {@code Quality} leaf's reference, read from the encoded schema the editor receives. */
    @Nonnull
    private static String encodedQualityRef(@Nonnull ObjectSchema schema) {
        BsonDocument encoded = Schema.CODEC.encode(schema, new ExtraInfo()).asDocument();
        return encoded.getDocument("properties").getDocument("Quality").getString("hytaleAssetRef").getValue();
    }
}
