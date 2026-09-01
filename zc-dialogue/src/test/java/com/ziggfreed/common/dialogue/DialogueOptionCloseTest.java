package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.type.DialogueAction;

/**
 * Pure-logic coverage for {@link DialogueOption#anyCloses}, the predicate behind the page's
 * implicit-"Farewell" row: an authored {@code "Close": true} option is real sugar for a genuine
 * {@link DialogueAction.Close} in that option's Actions list, so the page must not ALSO append
 * its own implicit "Farewell" row on top of it (the shipped duplicate-exit-row bug). Lives on
 * the pure model, not the page class, so a log-manager-less unit JVM can load it (the page's
 * engine super-class static init cannot run here). No server needed: decoding a dialogue body
 * through the engine is pure, same as DialogueEngineTest.
 */
class DialogueOptionCloseTest {

    /** The decode vocabulary is process-wide; start every test from a clean one. */
    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    private static List<DialogueOption> optionsOf(String nodeJson) {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("t", "{\"Nodes\":{\"g\":" + nodeJson + "}}");
        assertNotNull(d);
        return d.getNode("g").getOptions();
    }

    @Test
    void authoredCloseActionSuppressesImplicitFarewell() {
        List<DialogueOption> rendered = optionsOf(
                "{\"Options\":[{\"Label\":\"bye\",\"Actions\":[{\"Type\":\"Close\"}]}]}");
        assertTrue(DialogueOption.anyCloses(rendered));
    }

    @Test
    void gotoOnlyOptionNeedsImplicitFarewell() {
        List<DialogueOption> rendered = optionsOf(
                "{\"Options\":[{\"Label\":\"more\",\"Actions\":[{\"Type\":\"Goto\",\"Node\":\"g\"}]}]}");
        assertFalse(DialogueOption.anyCloses(rendered));
    }

    @Test
    void emptyOptionListNeedsImplicitFarewell() {
        assertFalse(DialogueOption.anyCloses(Collections.emptyList()));
    }

    @Test
    void farewellStyledButNonClosingOptionStillNeedsImplicitFarewell() {
        // Style:"farewell" is a themed LOOK override (DialogueOption.getStyleKind), not a real
        // exit; treating it as a closer would let the page suppress the only real way out of a
        // node whose sole "closer" is cosmetic. Anti soft-lock guard for the fix.
        List<DialogueOption> rendered = optionsOf(
                "{\"Options\":[{\"Label\":\"later\",\"Style\":\"farewell\","
                        + "\"Actions\":[{\"Type\":\"Goto\",\"Node\":\"g\"}]}]}");
        assertFalse(DialogueOption.anyCloses(rendered));
    }

    @Test
    void conditionFilteredCloseOptionIsAbsentFromRenderedList() {
        // The page never adds a condition-failed option to the rendered list in the first place
        // (build() only appends passing options), so a currently-hidden Close still yields the
        // implicit row for the options that ARE visible.
        List<DialogueOption> rendered = optionsOf(
                "{\"Options\":[{\"Label\":\"visible\",\"Actions\":[{\"Type\":\"Goto\",\"Node\":\"g\"}]}]}");
        assertFalse(DialogueOption.anyCloses(rendered));
    }
}
