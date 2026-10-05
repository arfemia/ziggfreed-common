package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.schema.DialogueExtension;
import com.ziggfreed.common.dialogue.schema.DialogueExtensionConfig;
import com.ziggfreed.common.dialogue.schema.DialogueSelector;
import com.ziggfreed.common.dialogue.schema.NodeSelector;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * An extension's lines are audited once, against the extension, never once per conversation they
 * reach; and what only an extension can get wrong is named.
 */
class DialogueExtensionAuditTest {

    private static final String GUIDE = """
            { "Start": { "Fallback": "menu" },
              "Fragments": { "pointers": { "On": { "Tags": ["steady"] }, "Options": [ { "LabelKey": "p" } ] } },
              "Nodes": { "menu": { "Options": [ { "LabelKey": "a" } ], "Tags": ["steady", "greeting"] } } }
            """;

    private static final String SMITH = """
            { "Start": { "Fallback": "shop" }, "Nodes": { "shop": { "Options": [ { "LabelKey": "s" } ] } } }
            """;

    /** A conversation with a screen none of its own lines reach. */
    private static final String HERMIT = """
            { "Start": { "Fallback": "door" },
              "Nodes": { "door": { "Options": [ { "LabelKey": "d" } ] },
                         "cellar": { "Options": [ { "LabelKey": "c" } ] } } }
            """;

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    /**
     * Leave no extension installed: the suite shares one JVM, and a test class that never resets
     * would otherwise find these lines spliced into its own conversations' opening screens.
     */
    @AfterEach
    void leaveNoExtensionInstalled() {
        DialogueExtensionConfig.getInstance().mergePackLayer(Map.of());
    }

    @Nonnull
    private static List<Finding> audit(@Nonnull String lines, @Nullable DialogueSelector dialogues,
                                       @Nullable NodeSelector on, boolean enabled) {
        DialogueEngine engine = DialogueEngine.builder().warn(m -> { }).build();
        DialogueTestSupport.shareExtensions(DialogueExtension.of("hallows_eve_trick",
                DialogueTestSupport.optionRows(lines), dialogues, on, enabled));
        NpcDialogue guide = engine.decode("guide", GUIDE);
        NpcDialogue smith = engine.decode("smith", SMITH);
        assertNotNull(guide);
        assertNotNull(smith);
        return DialogueStructureValidator.validateAll(List.of(guide, smith));
    }

    @Nonnull
    private static List<Finding> withCode(@Nonnull List<Finding> findings, @Nonnull String code) {
        return findings.stream().filter(f -> f.code().equals(code)).toList();
    }

    @Test
    void anInjectedLineIsAuditedOnceNotOncePerConversationItReaches() {
        List<Finding> findings = audit("[ { \"Once\": true } ]", null, null, true);
        List<Finding> noLabel = withCode(findings, "EXTENSION_NO_LABEL");
        assertEquals(1, noLabel.size(), DialogueTestSupport.codes(findings).toString());
        assertEquals(Severity.ERROR, noLabel.get(0).severity());
        assertEquals("hallows_eve_trick", noLabel.get(0).sourceId());
        List<Finding> noIdentity = withCode(findings, "ONCE_NO_IDENTITY");
        assertEquals(1, noIdentity.size(), "the Once audit runs once, against the extension: " + noIdentity);
        assertEquals("hallows_eve_trick", noIdentity.get(0).sourceId());
    }

    @Test
    void aGotoInAnInjectedLineIsAnErrorAndNotAHostsMissingScreen() {
        List<Finding> findings = audit("[ { \"LabelKey\": \"t\", \"Goto\": \"nowhere\" } ]", null, null, true);
        assertEquals(1, withCode(findings, "EXTENSION_GOTO").size());
        assertEquals(Severity.ERROR, withCode(findings, "EXTENSION_GOTO").get(0).severity());
        assertTrue(withCode(findings, "GOTO_MISSING_NODE").isEmpty(),
                "no host is blamed for a jump it never wrote: " + DialogueTestSupport.codes(findings));
    }

    @Test
    void aGotoInAnInjectedLineNeverMakesAHostsScreenReachable() {
        DialogueEngine engine = DialogueEngine.builder().warn(m -> { }).build();
        DialogueTestSupport.shareExtensions(DialogueExtension.of("hallows_eve_trick",
                DialogueTestSupport.optionRows("[ { \"LabelKey\": \"t\", \"Goto\": \"cellar\" } ]"), null, null, true));
        NpcDialogue hermit = engine.decode("hermit", HERMIT);
        assertNotNull(hermit);
        List<Finding> findings = DialogueStructureValidator.validateAll(List.of(hermit));
        assertTrue(withCode(findings, "EXTENSION_LANDS_NOWHERE").isEmpty(),
                "the line lands on the screen the hermit opens on: " + DialogueTestSupport.codes(findings));
        assertTrue(withCode(findings, "UNREACHABLE_NODE").stream().anyMatch(f -> f.message().contains("'cellar'")),
                "the hermit's own lines never reach the cellar: " + DialogueTestSupport.codes(findings));
    }

    @Test
    void aMemoryOnAnInjectedLineIsAWarningAndNoHostReportsIt() {
        List<Finding> findings = audit("[ { \"LabelKey\": \"t\", \"Remember\": \"candy\" } ]", null, null, true);
        assertEquals(1, withCode(findings, "EXTENSION_USES_MEMORY").size());
        assertEquals(Severity.WARNING, withCode(findings, "EXTENSION_USES_MEMORY").get(0).severity());
        assertTrue(withCode(findings, "MEMORY_UNDECLARED").isEmpty(), DialogueTestSupport.codes(findings).toString());
    }

    @Test
    void anIdNoLoadedConversationAnswersIsAWarning() {
        List<Finding> findings = audit("[ { \"LabelKey\": \"t\" } ]",
                DialogueSelector.of(new String[] {"Guide", "Nobody"}, null), null, true);
        List<Finding> unknown = withCode(findings, "EXTENSION_UNKNOWN_DIALOGUE");
        assertEquals(1, unknown.size(), "Guide is matched without regard to case: " + unknown);
        assertEquals(Severity.WARNING, unknown.get(0).severity());
        assertTrue(unknown.get(0).message().contains("'Nobody'"), unknown.get(0).message());
    }

    @Test
    void anOnWithNoPositiveAxisIsAnErrorAndTheExtensionReachesNothing() {
        List<Finding> findings = audit("[ { \"LabelKey\": \"t\" } ]", null,
                NodeSelector.of(null, null, new String[] {"menu"}), true);
        assertEquals(Severity.ERROR, withCode(findings, "EXTENSION_ON_NO_AXIS").get(0).severity());
        assertEquals(Severity.INFO, withCode(findings, "EXTENSION_LANDS_NOWHERE").get(0).severity());
    }

    @Test
    void aTagOnlyAnExtensionPlacesOnIsNotReportedUnused() {
        List<Finding> findings = audit("[ { \"LabelKey\": \"t\" } ]", null,
                NodeSelector.of(null, new String[] {"Greeting"}, null), true);
        assertTrue(withCode(findings, "NODE_TAG_UNUSED").stream().noneMatch(f -> f.message().contains("'greeting'")),
                "the extension places its line on that tag: " + withCode(findings, "NODE_TAG_UNUSED"));
    }

    @Test
    void aHealthyExtensionAddsNoFindingsAndADisabledOneIsNotAudited() {
        List<Finding> healthy = audit("""
                [ { "LabelKey": "hallows_eve.trick", "OnceId": "treat", "Once": { "Period": "Daily" }, "Close": true } ]
                """, null, null, true);
        assertTrue(healthy.stream().noneMatch(f -> f.sourceId().equals("hallows_eve_trick")), healthy.toString());

        DialogueTestSupport.reset();
        List<Finding> disabled = audit("[ { \"Goto\": \"nowhere\" } ]", null, null, false);
        assertTrue(disabled.stream().noneMatch(f -> f.code().startsWith("EXTENSION_")), disabled.toString());
    }
}
