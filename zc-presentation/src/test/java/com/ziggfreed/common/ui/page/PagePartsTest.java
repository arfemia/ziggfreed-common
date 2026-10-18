package com.ziggfreed.common.ui.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The part registry's codec dispatch: a {@code Sections} entry's group is read by the part registered under its
 * name when the file decodes (a part registered after the codec was made included), an entry naming two draws the
 * first in registration order, a group no part is named for is kept by name and skipped without failing the read,
 * and the editor is offered every registered part. Each test has a page of its own: the registry is shared.
 */
class PagePartsTest {

    /** A part's group: one number. */
    static final class Probe {

        private Integer value;

        static final BuilderCodec<Probe> CODEC = BuilderCodec.builder(Probe.class, Probe::new)
                .append(new KeyedCodec<>("Value", Codec.INTEGER, false), (p, v) -> p.value = v, p -> p.value).add()
                .build();
    }

    private static PagePart<Probe, Object> part(String name) {
        return PagePart.named(name, Probe.CODEC).documentation("The " + name + " part.")
                .paints(Object.class, "Pages/Test" + name + ".ui", (canvas, plan) -> { });
    }

    private static PageSection decode(Codec<PageSection> codec, String json) throws IOException {
        return codec.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void eachGroupIsReadByItsPartAndTheFirstRegisteredDraws() throws IOException {
        PageParts parts = PageParts.of("Test_Dispatch");
        Codec<PageSection> codec = parts.sectionCodec(PageSection.class, PageSection::new);
        parts.register(part("First"));
        parts.register(part("Second"));

        PageSection section = decode(codec, "{ \"Second\": { \"Value\": 2 }, \"$Comment\": \"a note\", "
                + "\"First\": { \"Value\": 1 } }");
        assertEquals(List.of("First", "Second"), section.partsWritten(), "registration order, not the order written");
        assertEquals("First", section.part());
        Probe drawn = section.drawn("First", Probe.class);
        assertNotNull(drawn, "the drawn part's group, read by its own codec");
        assertEquals(1, drawn.value);
        assertNull(section.drawn("Second", Probe.class), "a part written beside the drawn one is never handed out");
        assertEquals(List.of(), section.unknownParts(), "a $ key is no part and no unknown name");
    }

    @Test
    void aGroupNoPartIsNamedForIsKeptByNameAndSkipped() throws IOException {
        PageParts parts = PageParts.of("Test_Unknown");
        parts.register(part("Known"));
        Codec<PageSection> codec = parts.sectionCodec(PageSection.class, PageSection::new);

        PageSection none = decode(codec, "{ \"Picks\": { \"Anything\": [ 1, { \"Deep\": true } ] } }");
        assertNull(none.part(), "an entry naming no registered part draws nothing");
        assertEquals(List.of("Picks"), none.unknownParts(), "its name is kept for the page's one warning");

        PageSection beside = decode(codec, "{ \"Road\": {}, \"Known\": { \"Value\": 3 } }");
        assertEquals("Known", beside.part(), "a registered part beside an unknown name still draws");
        assertEquals(List.of("Road"), beside.unknownParts());
        assertEquals(3, beside.drawn("Known", Probe.class).value, "the reader is left where the next key begins");
    }

    @Test
    void theEditorIsOfferedEveryRegisteredPart() {
        PageParts parts = PageParts.of("Test_Schema");
        parts.register(part("Banner"));
        parts.register(part("Road"));
        Schema schema = parts.sectionCodec(PageSection.class, PageSection::new).toSchema(new SchemaContext());
        BsonDocument properties = Schema.CODEC.encode(schema, new ExtraInfo()).asDocument().getDocument("properties");
        assertTrue(properties.containsKey("Banner") && properties.containsKey("Road"), properties.keySet().toString());
    }
}
