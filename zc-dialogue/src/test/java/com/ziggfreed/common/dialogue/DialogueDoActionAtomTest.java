package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.DialogueSugar;
import com.ziggfreed.common.dialogue.schema.DialogueSugarLeaf;
import com.ziggfreed.common.dialogue.schema.DialogueSugarValues;
import com.ziggfreed.common.dialogue.schema.DialogueTypeTable;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.type.DialogueAction;
import com.ziggfreed.common.dialogue.type.DialogueActionExecutor;
import com.ziggfreed.common.dialogue.type.DialogueActionType;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A {@code Do} atom may carry one native step in full, so a step with no shorthand keeps its place
 * in the order the array spells; and the two ways an option's actions run in an order other than
 * the one the file reads as are named by the audit.
 */
class DialogueDoActionAtomTest {

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    @Nonnull
    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    @Nonnull
    private static DialogueOption firstOption(@Nonnull NpcDialogue d) {
        return d.getNode("g").getOptions().get(0);
    }

    @Nonnull
    private static Finding finding(@Nonnull NpcDialogue d, @Nonnull String code) {
        List<Finding> findings = DialogueStructureValidator.validate(d);
        Finding found = findings.stream().filter(f -> f.code().equals(code)).findFirst().orElse(null);
        assertNotNull(found, "expected a " + code + " finding in " + DialogueTestSupport.codes(findings));
        return found;
    }

    @Test
    void anActionAtomRunsWhereTheArrayPutsIt() {
        NpcDialogue d = engine().decode("guide", """
                { "Memories": { "greeted": {} },
                  "Start": { "Fallback": "g" },
                  "Nodes": { "g": { "Options": [ { "LabelKey": "hail",
                    "Do": [ { "Action": { "Type": "MarkTalked", "Target": "@self" } },
                            { "Remember": "greeted" },
                            { "Goto": "g" } ] } ] } } }
                """);
        assertNotNull(d);
        List<DialogueAction> actions = firstOption(d).getActions();
        assertEquals(3, actions.size());
        assertTrue(actions.get(0) instanceof DialogueAction.MarkTalked, "the native step first, as written");
        assertEquals("@self", ((DialogueAction.MarkTalked) actions.get(0)).getTarget());
        assertTrue(actions.get(1) instanceof DialogueAction.Remember);
        assertTrue(actions.get(2) instanceof DialogueAction.Goto);

        List<String> codes = DialogueTestSupport.codes(DialogueStructureValidator.validate(d));
        assertFalse(codes.contains("DO_ATOM_MIXED"), codes.toString());
        assertFalse(codes.contains("ACTIONS_BESIDE_DO"), codes.toString());
    }

    @Test
    void anAtomMixingActionAndShorthandIsReportedAndFoldsTheStepFirst() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Nodes": { "g": { "Options": [ { "LabelKey": "a",
                    "Do": [ { "Goto": "g", "Action": { "Type": "Close" } } ] } ] } } }
                """);
        assertNotNull(d);
        List<DialogueAction> actions = firstOption(d).getActions();
        assertEquals(2, actions.size());
        assertTrue(actions.get(0) instanceof DialogueAction.Close, "the native step folds first whichever was written first");
        assertTrue(actions.get(1) instanceof DialogueAction.Goto);

        Finding found = finding(d, "DO_ATOM_MIXED");
        assertEquals(Severity.WARNING, found.severity());
        assertTrue(found.message().contains("Do atom 0"), found.message());
    }

    @Test
    void actionsBesideDoIsReportedBecauseTheActionsRunFirstWhateverTheFileOrder() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Nodes": { "g": { "Options": [ { "LabelKey": "a",
                    "Do": [ { "Goto": "g" } ],
                    "Actions": [ { "Type": "Close" } ] } ] } } }
                """);
        assertNotNull(d);
        List<DialogueAction> actions = firstOption(d).getActions();
        assertTrue(actions.get(0) instanceof DialogueAction.Close, "Actions first, though Do was written first");
        assertTrue(actions.get(1) instanceof DialogueAction.Goto);

        Finding found = finding(d, "ACTIONS_BESIDE_DO");
        assertEquals(Severity.WARNING, found.severity());
        assertTrue(found.message().contains("Action"), "it points at the fix: " + found.message());
    }

    @Test
    void anOptionWithNeitherDoNorActionsReportsNothingNew() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Nodes": { "g": { "Options": [ { "LabelKey": "a", "Goto": "g" } ] } } }
                """);
        assertNotNull(d);
        List<String> codes = DialogueTestSupport.codes(DialogueStructureValidator.validate(d));
        assertFalse(codes.contains("DO_ATOM_MIXED"), codes.toString());
        assertFalse(codes.contains("ACTIONS_BESIDE_DO"), codes.toString());
    }

    /** No consumer can take the atom's own key for a shorthand; the table keeps the native step. */
    @Test
    void aShorthandMayNotClaimTheActionKey() {
        DialogueEngine engine = DialogueEngine.builder().warn(m -> { })
                .action(DialogueActionType.of("Nudge", Nudge.class, Nudge.CODEC,
                                (Nudge a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> { })
                        .withSugar(DialogueSugar.string(DialogueSugarValues.ACTION_KEY, 5, s -> new Nudge())))
                .build();
        for (DialogueSugarLeaf<?> leaf : DialogueTypeTable.get().sugar().leaves()) {
            assertFalse(DialogueSugarValues.ACTION_KEY.equals(leaf.key()), "the reserved key was refused");
        }
        NpcDialogue d = engine.decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Nodes": { "g": { "Options": [ { "LabelKey": "a",
                    "Do": [ { "Action": { "Type": "Close" } } ] } ] } } }
                """);
        assertNotNull(d);
        assertTrue(firstOption(d).getActions().get(0) instanceof DialogueAction.Close,
                "Action still reads as the native step");
    }

    public static final class Nudge extends DialogueAction {
        public static final BuilderCodec<Nudge> CODEC = BuilderCodec.builder(Nudge.class, Nudge::new).build();
    }
}
