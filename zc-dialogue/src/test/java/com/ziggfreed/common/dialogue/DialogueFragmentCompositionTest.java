package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.schema.DialogueFragmentGroup;
import com.ziggfreed.common.dialogue.schema.DialogueNode;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A shared option group can say where it belongs, and can fold in other groups.
 *
 * <p>The two mechanisms and the order they compose in are what these tests pin: a screen's own
 * lines first, then every group whose {@code On} selects the screen (declaration order), then every
 * group the screen's {@code IncludeOptions} names (written order); a group's lines are its own
 * followed by what it includes, recursively; a loop is dropped where it re-enters rather than
 * followed; and the audit names every way the wiring can be silently wrong.
 */
class DialogueFragmentCompositionTest {

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    @Nonnull
    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    @Nonnull
    private static List<String> labels(@Nonnull NpcDialogue d, @Nonnull String node) {
        DialogueNode screen = d.getNode(node);
        assertNotNull(screen, "screen '" + node + "' must exist");
        List<String> out = new ArrayList<>();
        for (DialogueOption option : screen.getOptions()) {
            out.add(option.getLabelKey());
        }
        return out;
    }

    @Nonnull
    private static List<String> codes(@Nonnull NpcDialogue d) {
        return DialogueTestSupport.codes(DialogueStructureValidator.validate(d));
    }

    @Nonnull
    private static Finding finding(@Nonnull NpcDialogue d, @Nonnull String code) {
        List<Finding> findings = DialogueStructureValidator.validate(d);
        Finding found = findings.stream().filter(f -> f.code().equals(code)).findFirst().orElse(null);
        assertNotNull(found, "expected a " + code + " finding in " + DialogueTestSupport.codes(findings));
        return found;
    }

    // ==================== placement through On ====================

    @Test
    void aGroupPlacedOnATagLandsAfterTheScreensOwnLinesAndBeforeThePulledOnes() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "First": [ { "Node": "h" } ], "Fallback": "g" },
                  "Fragments": {
                    "pointers": { "On": { "Tags": ["steady"] }, "Options": [ { "LabelKey": "p" } ] },
                    "footer": [ { "LabelKey": "f" } ] },
                  "Nodes": { "g": { "Options": [ { "LabelKey": "a" } ],
                                    "Tags": ["Steady"],
                                    "IncludeOptions": ["footer"] },
                             "h": { "Options": [ { "LabelKey": "b" } ] } } }
                """);
        assertNotNull(d);
        assertEquals(List.of("a", "p", "f"), labels(d, "g"),
                "own lines, then the pushed group, then the pulled footer; the tag matched across casing");
        assertEquals(List.of("b"), labels(d, "h"), "a screen without the tag is untouched");
        assertTrue(codes(d).isEmpty(), codes(d).toString());
    }

    @Test
    void pushedGroupsLandInDeclarationOrder() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Fragments": {
                    "second": { "On": { "Tags": ["steady"] }, "Options": [ { "LabelKey": "s" } ] },
                    "first":  { "On": { "Nodes": ["g"] },     "Options": [ { "LabelKey": "n" } ] } },
                  "Nodes": { "g": { "Tags": ["steady"] } } }
                """);
        assertNotNull(d);
        assertEquals(List.of("s", "n"), labels(d, "g"),
                "the order groups are declared in, not the order of the axes that matched");
    }

    @Test
    void aGroupPlacedByIdCanTakeAScreenBackOutWithExclude() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Fragments": {
                    "note": { "On": { "Nodes": ["g", "h"], "Exclude": ["H"] }, "Options": [ { "LabelKey": "n" } ] } },
                  "Nodes": { "g": { "Options": [ { "LabelKey": "a" } ] },
                             "h": { "Options": [ { "LabelKey": "b" } ] } } }
                """);
        assertNotNull(d);
        assertEquals(List.of("a", "n"), labels(d, "g"));
        assertEquals(List.of("b"), labels(d, "h"), "Exclude is matched without regard to case too");
    }

    @Test
    void aGroupReachingAScreenBothWaysIsSplicedOnceWhereTheScreenNamedIt() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Fragments": {
                    "pointers": { "On": { "Tags": ["steady"] }, "Options": [ { "LabelKey": "p" } ] },
                    "footer": [ { "LabelKey": "f" } ] },
                  "Nodes": { "g": { "Options": [ { "LabelKey": "a" } ],
                                    "Tags": ["steady"],
                                    "IncludeOptions": ["footer", "pointers"] } } }
                """);
        assertNotNull(d);
        assertEquals(List.of("a", "f", "p"), labels(d, "g"),
                "the screen's own order wins, and the pointers appear once");
    }

    @Test
    void aSharedFileIsPullOnlyAndLandsOnlyWhereItIsNamed() {
        engine();
        DialogueTestSupport.shareFragments(Map.of("tail", options("t")));
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Nodes": { "g": { "Tags": ["tail"], "IncludeOptions": ["tail"] },
                             "h": { "Tags": ["tail"] } } }
                """);
        assertNotNull(d);
        assertEquals(List.of("t"), labels(d, "g"), "named, so it lands");
        assertTrue(labels(d, "h").isEmpty(), "a file has no On, so a tag spelt like its name means nothing");
    }

    // ==================== Include ====================

    @Test
    void aGroupsIncludedLinesFollowItsOwnRecursively() {
        engine();
        DialogueTestSupport.shareFragments(Map.of("shared_end", options("e")));
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Fragments": {
                    "footer": { "Options": [ { "LabelKey": "q" } ], "Include": ["tail"] },
                    "tail":   { "Options": [ { "LabelKey": "t" } ], "Include": ["Shared_End"] } },
                  "Nodes": { "g": { "Options": [ { "LabelKey": "a" } ], "IncludeOptions": ["footer"] } } }
                """);
        assertNotNull(d);
        assertEquals(List.of("a", "q", "t", "e"), labels(d, "g"),
                "own, then the included local group, then the shared file that group includes");
        assertTrue(codes(d).isEmpty(), codes(d).toString());
    }

    @Test
    void anIncludeLoopIsDroppedWhereItReentersAndIsAnError() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Fragments": {
                    "a": { "Options": [ { "LabelKey": "x" } ], "Include": ["b"] },
                    "b": { "Options": [ { "LabelKey": "y" } ], "Include": ["c"] },
                    "c": { "Options": [ { "LabelKey": "z" } ], "Include": ["a"] } },
                  "Nodes": { "g": { "IncludeOptions": ["a"] } } }
                """);
        assertNotNull(d, "a file that closes the loop still loads");
        assertEquals(List.of("x", "y", "z"), labels(d, "g"),
                "every line before the loop is in place and nothing repeats");

        List<Finding> findings = DialogueStructureValidator.validate(d);
        List<Finding> cycles = findings.stream().filter(f -> f.code().equals("FRAGMENT_CYCLE")).toList();
        assertEquals(1, cycles.size(), "one loop is one finding, reported from the group it closes on: "
                + DialogueTestSupport.codes(findings));
        assertEquals(Severity.ERROR, cycles.get(0).severity());
        assertTrue(cycles.get(0).message().contains("a -> b -> c"), cycles.get(0).message());
    }

    @Test
    void aGroupIncludingItselfDirectlyIsHandledTheSameWay() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Fragments": { "a": { "Options": [ { "LabelKey": "x" } ], "Include": ["a"] } },
                  "Nodes": { "g": { "IncludeOptions": ["a"] } } }
                """);
        assertNotNull(d);
        assertEquals(List.of("x"), labels(d, "g"));
        assertEquals(Severity.ERROR, finding(d, "FRAGMENT_CYCLE").severity());
    }

    @Test
    void anIncludeNothingAnswersIsTheSameFindingAScreenGets() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Fragments": { "footer": { "Options": [ { "LabelKey": "q" } ], "Include": ["typo"] } },
                  "Nodes": { "g": { "IncludeOptions": ["footer"] } } }
                """);
        assertNotNull(d);
        assertEquals(List.of("q"), labels(d, "g"), "the group's own lines still land");
        Finding found = finding(d, "UNKNOWN_FRAGMENT");
        assertTrue(found.message().contains("'footer' includes"), found.message());
    }

    // ==================== the audit ====================

    @Test
    void anOnWithNoPositiveAxisIsAnError() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Fragments": { "note": { "On": { "Exclude": ["g"] }, "Options": [ { "LabelKey": "n" } ] } },
                  "Nodes": { "g": { "Options": [] } } }
                """);
        assertNotNull(d);
        assertEquals(Severity.ERROR, finding(d, "FRAGMENT_ON_NO_AXIS").severity());
        assertTrue(labels(d, "g").isEmpty(), "and it really lands nowhere");
    }

    @Test
    void anOnNamingAScreenNothingDeclaresIsAnError() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Fragments": { "note": { "On": { "Nodes": ["g", "nope"], "Exclude": ["gone"] },
                                           "Options": [ { "LabelKey": "n" } ] } },
                  "Nodes": { "g": { "Options": [] } } }
                """);
        assertNotNull(d);
        List<Finding> findings = DialogueStructureValidator.validate(d);
        List<String> messages = findings.stream()
                .filter(f -> f.code().equals("FRAGMENT_ON_MISSING_NODE"))
                .map(Finding::message).toList();
        assertEquals(2, messages.size(), "one per exact id that exists nowhere: " + messages);
        assertTrue(messages.get(0).contains("'nope'") && messages.get(0).contains("On.Nodes"), messages.get(0));
        assertTrue(messages.get(1).contains("'gone'") && messages.get(1).contains("On.Exclude"), messages.get(1));
    }

    @Test
    void aTagNoScreenCarriesIsAWarningAndAScreenTagNoGroupNamesIsInformation() {
        NpcDialogue d = engine().decode("guide", """
                { "Start": { "Fallback": "g" },
                  "Fragments": { "note": { "On": { "Tags": ["stedy"] }, "Options": [ { "LabelKey": "n" } ] } },
                  "Nodes": { "g": { "Options": [], "Tags": ["steady"] } } }
                """);
        assertNotNull(d);
        Finding unknown = finding(d, "FRAGMENT_TAG_UNKNOWN");
        assertEquals(Severity.WARNING, unknown.severity());
        assertTrue(unknown.message().contains("'stedy'"), "the typo is named: " + unknown.message());
        Finding unused = finding(d, "NODE_TAG_UNUSED");
        assertEquals(Severity.INFO, unused.severity());
        assertTrue(unused.message().contains("'steady'"), unused.message());
    }

    // ==================== Parent ====================

    @Test
    void aChildRestatingOnlyOnKeepsTheParentsLinesWhileAnArrayReplacesTheGroupWhole() throws Exception {
        DialogueEngine engine = engine();
        NpcDialogue parent = engine.decode("base", """
                { "Start": { "Fallback": "g" },
                  "Fragments": {
                    "pointers": { "On": { "Tags": ["steady"] }, "Options": [ { "LabelKey": "p" } ] },
                    "footer": [ { "LabelKey": "f" } ] },
                  "Nodes": { "g": { "Tags": ["steady"], "IncludeOptions": ["footer"] },
                             "h": { "Tags": ["other"], "IncludeOptions": ["footer"] } } }
                """);
        assertNotNull(parent);
        assertEquals(List.of("p", "f"), labels(parent, "g"));

        NpcDialogue kid = DialogueTestSupport.decodeWithParent(engine, "kid", """
                { "Fragments": { "pointers": { "On": { "Tags": ["other"] } },
                                 "footer": [ { "LabelKey": "kid.f" } ] } }
                """, parent);
        assertNotNull(kid);
        DialogueFragmentGroup pointers = kid.getFragments().get("pointers");
        assertNotNull(pointers);
        assertEquals(List.of("other"), pointers.getOn().getTags(), "the child's On replaced the leaf");
        assertEquals(1, pointers.getOptions().size(), "and the parent's lines came along");
        assertEquals("p", pointers.getOptions().get(0).getLabelKey());
        assertEquals(List.of("kid.f"), labels(kid, "g"), "the pointers moved off g with the tag");
        assertEquals(List.of("p", "kid.f"), labels(kid, "h"), "and onto h; the array footer replaced whole");
        assertEquals(List.of("p", "f"), labels(parent, "g"), "the parent conversation is untouched");
    }

    @Test
    void aChildAddsAGroupOntoInheritedScreensByTagWithoutRestatingAny() throws Exception {
        DialogueEngine engine = engine();
        NpcDialogue parent = engine.decode("base", """
                { "Start": { "Fallback": "g" },
                  "Nodes": { "g": { "Options": [ { "LabelKey": "a" } ], "Tags": ["steady"] } } }
                """);
        assertNotNull(parent);

        NpcDialogue kid = DialogueTestSupport.decodeWithParent(engine, "kid", """
                { "Fragments": { "extra": { "On": { "Tags": ["steady"] }, "Options": [ { "LabelKey": "e" } ] } } }
                """, parent);
        assertNotNull(kid);
        assertEquals(List.of("a", "e"), labels(kid, "g"), "one group entry, no screen named");
        assertEquals(List.of("a"), labels(parent, "g"), "the parent's own screen still says what its file said");
        assertNull(parent.getFragments().get("extra"));
    }

    // ==================== helpers ====================

    @Nonnull
    private static DialogueOption[] options(@Nonnull String... labelKeys) {
        List<DialogueOption> out = new ArrayList<>();
        for (String key : labelKeys) {
            NpcDialogue holder = engine().decode("holder",
                    "{\"Nodes\":{\"n\":{\"Options\":[{\"LabelKey\":\"" + key + "\"}]}}}");
            assertNotNull(holder);
            out.add(holder.getNode("n").getOptions().get(0));
        }
        assertFalse(out.isEmpty());
        return out.toArray(new DialogueOption[0]);
    }
}
