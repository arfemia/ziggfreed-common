package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The validator findings for the generic state surface: every way a {@code Once} or a declared
 * {@code Memories} entry can be quietly wrong. Each of these is otherwise SILENT at runtime - the
 * content simply never behaves as authored - which is why they are findings at all.
 */
class DialogueStateValidationTest {

    /** The decode vocabulary is process-wide; start every test from a clean one. */
    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    @Nonnull
    private static List<String> codes(@Nonnull List<Finding> findings) {
        return DialogueTestSupport.codes(findings);
    }

    @Nonnull
    private static Finding issue(@Nonnull List<Finding> findings, @Nonnull String code) {
        Finding found = findings.stream().filter(f -> f.code().equals(code)).findFirst().orElse(null);
        assertNotNull(found, "expected a " + code + " finding in " + codes(findings));
        return found;
    }

    @Test
    void usingAMemoryWithoutDeclaringItIsAnError() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Start\":[{\"Node\":\"g\"}],"
                + "\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\","
                + "\"Conditions\":[{\"Type\":\"Remembered\",\"Memory\":\"helped\"}],"
                + "\"Actions\":[{\"Type\":\"Remember\",\"Memory\":\"helped\"}]}]}}}");
        assertNotNull(d);

        List<Finding> issues = DialogueStructureValidator.validate(d);
        assertEquals(Severity.ERROR, issue(issues, "MEMORY_UNDECLARED").severity());
    }

    @Test
    void aMemoryNestedInACombinatorIsStillSeen() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Memories\":{\"helped\":{}},"
                + "\"Start\":[{\"Node\":\"g\",\"Conditions\":[{\"Type\":\"AnyOf\",\"Any\":["
                + "{\"Type\":\"Remembered\",\"Memory\":\"helped\"}]}]}],"
                + "\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\","
                + "\"Actions\":[{\"Type\":\"Remember\",\"Memory\":\"helped\"}]}]}}}");
        assertNotNull(d);

        List<String> codes = codes(DialogueStructureValidator.validate(d));
        assertFalse(codes.contains("MEMORY_UNDECLARED"), codes.toString());
        assertFalse(codes.contains("MEMORY_NEVER_READ"), codes.toString());
        assertFalse(codes.contains("MEMORY_NEVER_WRITTEN"), codes.toString());
    }

    @Test
    void aDeclarationNothingWritesWarnsAndOneNothingReadsInforms() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Memories\":{\"unused\":{},\"write_only\":{}},"
                + "\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\","
                + "\"Actions\":[{\"Type\":\"Remember\",\"Memory\":\"write_only\"}]}]}}}");
        assertNotNull(d);

        List<Finding> issues = DialogueStructureValidator.validate(d);
        assertEquals(Severity.WARNING, issue(issues, "MEMORY_NEVER_WRITTEN").severity());
        assertEquals(Severity.INFO, issue(issues, "MEMORY_NEVER_READ").severity());
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("MEMORY_NEVER_WRITTEN")
                && i.message().contains("unused")));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("MEMORY_NEVER_READ")
                && i.message().contains("write_only")));
    }

    @Test
    void aBlankMemoryNameIsAnError() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Memories\":{\"  \":{}},"
                + "\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\","
                + "\"Actions\":[{\"Type\":\"Remember\"}]}]}}}");
        assertNotNull(d);

        List<Finding> issues = DialogueStructureValidator.validate(d);
        assertEquals(Severity.ERROR, issue(issues, "MEMORY_BLANK_NAME").severity());
    }

    @Test
    void twoDialoguesDisagreeingAboutASharedMemoryIsAnError() {
        DialogueEngine engine = engine();
        String body = "{\"Memories\":{\"trust\":{\"Shared\":true%s}},"
                + "\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\","
                + "\"Conditions\":[{\"Type\":\"Remembered\",\"Memory\":\"trust\"}],"
                + "\"Actions\":[{\"Type\":\"Remember\",\"Memory\":\"trust\"}]}]}}}";
        NpcDialogue first = engine.decode("guide_a", String.format(body, ""));
        NpcDialogue agreeing = engine.decode("guide_b", String.format(body, ""));
        NpcDialogue disagreeing = engine.decode("guide_c",
                String.format(body, ",\"ResetWithQuest\":\"guide_trust\""));
        assertNotNull(first);
        assertNotNull(agreeing);
        assertNotNull(disagreeing);

        List<String> agreed = codes(DialogueStructureValidator.validateAll(List.of(first, agreeing)));
        assertFalse(agreed.contains("MEMORY_SHARED_MISMATCH"), agreed.toString());

        List<Finding> mismatched =
                DialogueStructureValidator.validateAll(List.of(first, disagreeing));
        Finding found = issue(mismatched, "MEMORY_SHARED_MISMATCH");
        assertEquals(Severity.ERROR, found.severity());
        assertEquals("guide_c", found.sourceId());
    }

    @Test
    void anUnknownSelectorOnAMemoryDeclarationIsAnError() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide",
                "{\"Memories\":{\"helped\":{\"WorldSelector\":\"emrald_wilds\"}},"
                        + "\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\","
                        + "\"Conditions\":[{\"Type\":\"Remembered\",\"Memory\":\"helped\"}],"
                        + "\"Actions\":[{\"Type\":\"Remember\",\"Memory\":\"helped\"}]}]}}}");
        assertNotNull(d);

        List<Finding> issues = DialogueStructureValidator.validate(d, Set.of("emerald_wilds"));
        assertEquals(Severity.ERROR, issue(issues, "MEMORY_UNKNOWN_SELECTOR").severity());

        // "Cannot tell" (no vocabulary loaded yet) must never produce the finding.
        assertFalse(codes(DialogueStructureValidator.validate(d)).contains("MEMORY_UNKNOWN_SELECTOR"));
    }

    @Test
    void anUnknownSelectorOnAnOptionOnceIsAnError() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Start\":[{\"Node\":\"g\"}],"
                + "\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\","
                + "\"Once\":{\"WorldSelector\":\"emrald_wilds\"}}]}}}");
        assertNotNull(d);

        List<Finding> issues = DialogueStructureValidator.validate(d, Set.of("emerald_wilds"));
        assertEquals(Severity.ERROR, issue(issues, "ONCE_UNKNOWN_SELECTOR").severity());
    }

    @Test
    void aOnceWithNothingToIdentifyItWarns() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Start\":[{\"Node\":\"g\"}],"
                + "\"Nodes\":{\"g\":{\"Options\":[{\"Once\":{}},{\"LabelKey\":\"fine\",\"Once\":{}}]}}}");
        assertNotNull(d);

        List<Finding> issues = DialogueStructureValidator.validate(d);
        Finding found = issue(issues, "ONCE_NO_IDENTITY");
        assertEquals(Severity.WARNING, found.severity());
        assertEquals(1, issues.stream().filter(i -> i.code().equals("ONCE_NO_IDENTITY")).count(),
                "the option with a LabelKey is fine");
    }

    @Test
    void twoOnceOptionsSharingOneIdentityInANodeAreAnError() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Start\":[{\"Node\":\"g\"}],"
                + "\"Nodes\":{\"g\":{\"Options\":["
                + "{\"LabelKey\":\"opt.bread\",\"Once\":{}},"
                + "{\"LabelKey\":\"OPT.BREAD\",\"Once\":{}},"
                + "{\"LabelKey\":\"opt.bread\",\"OnceId\":\"bread_again\",\"Once\":{}},"
                + "{\"LabelKey\":\"opt.other\"}]}}}");
        assertNotNull(d);

        List<Finding> issues = DialogueStructureValidator.validate(d);
        Finding found = issue(issues, "ONCE_DUPLICATE_IDENTITY");
        assertEquals(Severity.ERROR, found.severity());
        assertEquals(1, issues.stream().filter(i -> i.code().equals("ONCE_DUPLICATE_IDENTITY")).count(),
                "the OnceId option has its own identity and the Once-less option cannot collide");
    }

    @Test
    void aCleanTreeReportsNothingAboutItsState() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide",
                "{\"Memories\":{\"helped\":{\"WorldSelector\":\"emerald_wilds\"}},"
                        + "\"Start\":[{\"Node\":\"g\",\"Once\":{\"WorldSelector\":\"emerald_wilds\"}}],"
                        + "\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Once\":{},"
                        + "\"Conditions\":[{\"Type\":\"NotRemembered\",\"Memory\":\"helped\"}],"
                        + "\"Actions\":[{\"Type\":\"Remember\",\"Memory\":\"helped\"}]}]}}}");
        assertNotNull(d);

        List<String> codes = codes(DialogueStructureValidator.validateAll(List.of(d),
                Set.of("emerald_wilds")));
        assertTrue(codes.isEmpty(), codes.toString());
    }
}
