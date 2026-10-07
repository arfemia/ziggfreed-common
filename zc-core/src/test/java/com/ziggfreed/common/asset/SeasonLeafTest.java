package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The one {@code Season} leaf: read under its key, carried down {@code Parent} whatever else a child
 * writes, overridable by a child, and a string with a sentence in the schema the Asset Editor loads.
 */
class SeasonLeafTest {

    /** A stand-in for any store's asset: one sibling leaf plus the season. */
    static final class Holder {
        @Nullable String season;
        @Nullable String other;
    }

    private static final BuilderCodec<Holder> CODEC = SeasonLeaf.append(
            BuilderCodec.builder(Holder.class, Holder::new)
                    .appendInherited(new KeyedCodec<>("Other", Codec.STRING, false),
                            (o, v) -> o.other = v, o -> o.other, (o, p) -> o.other = p.other)
                    .add(),
            (o, v) -> o.season = v, o -> o.season)
            .build();

    private static Holder decode(String json, @Nullable Holder parent) throws IOException {
        return CODEC.decodeAndInheritJson(RawJsonReader.fromJsonString(json), parent, new ExtraInfo());
    }

    @Test
    void theLeafIsReadUnderSeasonAndUnauthoredIsNull() throws IOException {
        assertEquals("Season", SeasonLeaf.KEY);
        assertEquals("Harvest_Feast", decode("{ \"Season\": \"Harvest_Feast\" }", null).season);
        assertNull(decode("{}", null).season);
    }

    @Test
    void aChildKeepsItsParentsSeasonWhateverElseItWrites() throws IOException {
        Holder base = decode("{ \"Season\": \"Harvest_Feast\", \"Other\": \"base\" }", null);

        Holder child = decode("{ \"Other\": \"child\" }", base);

        assertEquals("Harvest_Feast", child.season, "inherited, unlike a Requires array the child replaced");
        assertEquals("child", child.other);
    }

    @Test
    void aChildMayNameItsOwnSeason() throws IOException {
        Holder base = decode("{ \"Season\": \"Harvest_Feast\" }", null);

        assertEquals("Winter_Festival", decode("{ \"Season\": \"Winter_Festival\" }", base).season);
    }

    @Test
    void theSchemaIsAStringLeafWithASentence() {
        ObjectSchema schema = (ObjectSchema) CODEC.toSchema(new SchemaContext());
        Schema leaf = schema.getProperties().get(SeasonLeaf.KEY);

        assertNotNull(leaf, "the editor lists the leaf");
        assertTrue(leaf instanceof StringSchema, "a free string: no calendar-event dataset is served");
        assertNotNull(leaf.getMarkdownDescription(), "an author reads what it does");
    }
}
