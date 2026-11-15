package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;

/**
 * One kind whose form cannot be made costs that kind alone: the Asset Editor is sent every schema at once and
 * one it cannot read leaves every type unusable, so a selector leaves the broken value off its list and still
 * offers the rest, the any-other arm taking the dropped value as typed.
 */
class KindSelectorCodecTest {

    /** A row whose Kind picks its form. */
    private static final class Row {
        String kind;
        String target;
    }

    private static final BuilderCodec<Row> ROW = BuilderCodec.builder(Row.class, Row::new)
            .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                    (o, v) -> o.kind = v, o -> o.kind, (o, p) -> o.kind = p.kind).add()
            .appendInherited(new KeyedCodec<>("Target", Codec.STRING, false),
                    (o, v) -> o.target = v, o -> o.target, (o, p) -> o.target = p.target).add()
            .build();

    @Test
    void anArmWhoseFormThrowsIsLeftOffAndTheRestStayReadable() {
        KindSelectorCodec<Row> rows = new KindSelectorCodec<>(ROW, "Kind", () -> List.of(
                new KindSelectorCodec.Arm("Good", null,
                        (arm, context) -> arm.getProperties().put("Target", new StringSchema())),
                new KindSelectorCodec.Arm("Broken", null, (arm, context) -> {
                    throw new IllegalStateException("a form that cannot be made");
                })));
        SchemaContext context = new SchemaContext();

        Schema selector = rows.toSchema(context);

        assertArrayEquals(new String[] {"Good"}, selector.getHytaleSchemaTypeField().getValues(),
                "the broken value is left off the Kind list");
        assertTrue(context.getDefinitions().containsKey("Row@Good"), "the good value keeps its form");
        assertTrue(context.getDefinitions().keySet().stream().noneMatch(name -> name.endsWith("@Broken")),
                "the broken value files no form");
        assertTrue(context.getDefinitions().containsKey("Row@@other"), "the any-other arm takes it as typed");

        Schema common = new Schema();
        common.setDefinitions(context.getDefinitions());
        common.setId(EditorSchemaCheck.COMMON);
        selector.setId("Rows.json");
        assertEquals(List.of(), EditorSchemaCheck.check(Map.of(EditorSchemaCheck.COMMON, common, "Rows.json", selector))
                .stream().map(EditorSchemaCheck.Problem::toString).toList());
    }
}
