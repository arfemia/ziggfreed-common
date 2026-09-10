package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.ziggfreed.common.dialogue.quest.QuestDialogueConditions;
import com.ziggfreed.common.dialogue.schema.DialogueTypeTable;
import com.ziggfreed.common.dialogue.style.DialogueOptionStyle;
import com.ziggfreed.common.dialogue.type.DialogueAction;
import com.ziggfreed.common.dialogue.type.DialogueCondition;
import com.ziggfreed.common.quest.QuestStatus;

/**
 * What the exported schema says to the in-game Asset Editor: a union declares every arm it decodes,
 * each with its own type; a closed vocabulary is a dropdown; every array leaf says what it holds;
 * and the leaves whose meaning is easy to get wrong carry a sentence.
 */
class DialogueEditorSchemaTest {

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
        DialogueEngine.builder().warn(m -> { }).build();
    }

    @Test
    void aFragmentsEntryDeclaresTheArrayArmAndTheObjectArmEachTyped() {
        SchemaContext context = new SchemaContext();
        ObjectSchema fragments = (ObjectSchema) DialogueTypeTable.get().fragmentsCodec().toSchema(context);
        Schema value = (Schema) fragments.getAdditionalProperties();
        assertNotNull(value.getAnyOf(), "an entry reads as an array or an object, so the schema is a union");
        assertEquals(2, value.getAnyOf().length);

        ArraySchema lines = (ArraySchema) value.getAnyOf()[0];
        assertNotNull(lines.getItems(), "the array arm says what it holds");
        assertEquals("Lines only", lines.getTitle());

        ObjectSchema group = (ObjectSchema) value.getAnyOf()[1];
        Map<String, Schema> leaves = group.getProperties();
        assertTrue(leaves.containsKey("Options") && leaves.containsKey("On") && leaves.containsKey("Include"),
                leaves.keySet().toString());
        ArraySchema include = (ArraySchema) leaves.get("Include");
        assertNotNull(include.getItems());
        // A nested group is filed as a definition and referenced from the leaf, the engine's own
        // way; an untyped leaf is wrapped as nullable, so the reference sits inside that wrapper.
        Schema onLeaf = leaves.get("On");
        Schema onRef = onLeaf.getAnyOf() == null ? onLeaf : onLeaf.getAnyOf()[0];
        assertNotNull(onRef.getRef(), "the selector is a reference to its definition");
        ObjectSchema on = definition(context, "NodeSelector");
        assertTrue(on.getProperties().keySet().containsAll(List.of("Nodes", "Tags", "Exclude")),
                on.getProperties().keySet().toString());
        assertNotNull(((ArraySchema) on.getProperties().get("Tags")).getItems());
    }

    @Test
    void aScreensTagsLeafIsAStringArrayWithItems() {
        SchemaContext context = new SchemaContext();
        DialogueTypeTable.get().nodesCodec().toSchema(context);
        ObjectSchema node = definition(context, "DialogueNode");
        ArraySchema tags = (ArraySchema) node.getProperties().get("Tags");
        assertNotNull(tags, "a screen declares its tags on its own leaf");
        assertNotNull(tags.getItems());
        assertNotNull(tags.getMarkdownDescription());
    }

    @Test
    void anOptionsStyleIsADropdownOfTheFiveKinds() {
        SchemaContext context = new SchemaContext();
        DialogueTypeTable.get().optionsArray().toSchema(context);
        ObjectSchema option = definition(context, "DialogueOption");
        StringSchema style = (StringSchema) option.getProperties().get("Style");
        assertArrayEquals(DialogueOptionStyle.keys(), style.getEnum(),
                "the reader ignores anything but these five, so the editor may offer them as a list");
        assertArrayEquals(new String[] {"accept", "turnin", "continue", "neutral", "farewell"},
                DialogueOptionStyle.keys());
    }

    @Test
    void aQuestStateLeafIsADropdownOfEveryStatusWithItsMeaning() {
        ObjectSchema questState = QuestDialogueConditions.QuestState.CODEC.toSchema(new SchemaContext());
        String[] expected = new String[QuestStatus.values().length];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = QuestStatus.values()[i].name();
        }
        StringSchema state = (StringSchema) questState.getProperties().get("State");
        assertArrayEquals(expected, state.getEnum(), "the vocabulary is the enum's own, in its order");
        assertNotNull(state.getMarkdownEnumDescriptions());
        assertEquals(expected.length, state.getMarkdownEnumDescriptions().length);

        ArraySchema states = (ArraySchema) questState.getProperties().get("States");
        StringSchema each = (StringSchema) states.getItems();
        assertArrayEquals(expected, each.getEnum(), "the same list applies to every entry of States");
    }

    @Test
    void theMemoryAndGotoLeavesSayWhatTheyMean() {
        SchemaContext context = new SchemaContext();
        assertDocumented(DialogueAction.Goto.CODEC.toSchema(context), "Node");
        assertDocumented(DialogueAction.Remember.CODEC.toSchema(context), "Memory");
        assertDocumented(DialogueAction.Forget.CODEC.toSchema(context), "Memory");
        assertDocumented(DialogueCondition.Remembered.CODEC.toSchema(context), "Memory");
        assertDocumented(DialogueCondition.NotRemembered.CODEC.toSchema(context), "Memory");

        String remember = DialogueAction.Remember.CODEC.toSchema(context).getProperties().get("Memory")
                .getMarkdownDescription();
        assertTrue(remember.contains("does nothing"), "it names the per-world no-op: " + remember);
    }

    /** A field's documentation is exported as the schema's markdown description. */
    private static void assertDocumented(@Nonnull ObjectSchema schema, @Nonnull String leaf) {
        Schema property = schema.getProperties().get(leaf);
        assertNotNull(property, leaf);
        assertNotNull(property.getMarkdownDescription(), leaf + " carries no sentence");
        assertTrue(!property.getMarkdownDescription().isBlank(), leaf + " carries a blank sentence");
    }

    /** The raw definition the context filed under a codec whose class has that simple name. */
    @Nonnull
    private static ObjectSchema definition(@Nonnull SchemaContext context, @Nonnull String simpleName) {
        for (Map.Entry<String, Schema> entry : context.getDefinitions().entrySet()) {
            if (entry.getKey().endsWith(simpleName) && entry.getValue() instanceof ObjectSchema object) {
                return object;
            }
        }
        throw new AssertionError("no definition named like " + simpleName + " in "
                + context.getDefinitions().keySet());
    }
}
