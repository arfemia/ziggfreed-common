package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.dialogue.asset.DialogueFragmentAsset;
import com.ziggfreed.common.dialogue.asset.ZcDialogueAsset;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;

/**
 * What a conversation FILE looks like, and what the engine makes of it.
 *
 * <p>Every field of a conversation is a field of the file: {@code Start}, {@code Nodes},
 * {@code Memories} and {@code Fragments} sit at the top level beside {@code Enabled} and
 * {@code Abstract}, which is what the in-game asset editor offers and what a hand-written file reads
 * as. The two properties worth pinning are that each of those fields keeps its OWN behaviour under
 * {@code Parent} (screens merge by name, greetings replace whole) and that a screen can pull in
 * lines from a file other conversations share.
 */
class DialogueAssetShapeTest {

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    /**
     * Build the generic engine, which is what publishes the framework's own action, condition and
     * shorthand vocabulary into the process-wide table. No file can be read before that has
     * happened, which is why every test here starts by calling this even when it never touches the
     * engine again.
     */
    @Nonnull
    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    // ==================== the file's own shape ====================

    @Test
    void everyConversationFieldIsAFieldOfTheFile() {
        engine();
        ZcDialogueAsset asset = read("guide", """
                {
                  "Name": "Guide",
                  "Memories": { "greeted": { "Where": { "Match": ["*Temple*"] } } },
                  "Start": { "First": [ { "Node": "greet" } ] },
                  "Fragments": { "footer": [ { "LabelKey": "bye", "Close": true } ] },
                  "Nodes": { "greet": { "TextKey": "dialogue.guide.greet.text",
                                        "Options": [ { "LabelKey": "hello" } ],
                                        "IncludeOptions": [ "footer" ] } }
                }
                """);

        assertTrue(asset.isEnabled(), "unauthored Enabled means in circulation");
        assertFalse(asset.isAbstract());

        NpcDialogue d = asset.getDialogue();
        assertNotNull(d);
        assertEquals("guide", d.getId(), "the conversation carries the file's id");
        assertEquals("greet", d.getStart().first().get(0).getNode());
        assertEquals("dialogue.guide.greet.text", d.getNode("greet").getTextKey());
        assertNotNull(d.getMemory("greeted"));
        assertEquals("*Temple*", d.getMemory("greeted").getWhere().getMatch()[0]);
        // The shared group this file declares is spliced onto the screen that names it.
        assertEquals(2, d.getNode("greet").getOptions().size());
        assertEquals("bye", d.getNode("greet").getOptions().get(1).getLabelKey());
    }

    @Test
    void aFileWithNoConversationFieldsIsNotAConversation() {
        engine();
        ZcDialogueAsset asset = read("empty", "{ \"Name\": \"Empty\", \"Enabled\": false }");
        assertNull(asset.getDialogue(), "nothing to open, so the store has nothing to hand out");
        assertFalse(asset.isEnabled());
    }

    // ==================== Parent, per field ====================

    @Test
    void screensAndMemoriesMergeByNameUnderParentWhileGreetingsReplace() {
        engine();
        ZcDialogueAsset base = read("base", """
                {
                  "Memories": { "met": { "Shared": true } },
                  "Start": { "First": [ { "Node": "greet" }, { "Node": "fallback" } ] },
                  "Nodes": {
                    "greet": { "Text": "base greet", "Options": [ { "Label": "a" } ] },
                    "bye": { "Text": "base bye", "Options": [] },
                    "fallback": { "Text": "base fallback", "Options": [] } }
                }
                """);
        assertNotNull(base.getDialogue());

        // The child restates one screen's TEXT, adds a screen, adds a memory, and writes its own
        // greeting ladder. It says nothing about the other screens or the parent's memory.
        ZcDialogueAsset child = readWithParent("kid", """
                {
                  "Memories": { "helped": { "ResetWithQuest": "trust" } },
                  "Start": { "First": [ { "Node": "greet" } ] },
                  "Nodes": {
                    "greet": { "Text": "kid greet" },
                    "extra": { "Text": "new", "Options": [] } }
                }
                """, base);

        NpcDialogue d = child.getDialogue();
        assertNotNull(d);

        // Nodes merge BY NAME: the restated screen keeps the options it did not mention, the two
        // screens it never named survive, and the new one is added.
        assertEquals(4, d.getNodes().size());
        assertEquals("kid greet", d.getNode("greet").getText());
        assertEquals(1, d.getNode("greet").getOptions().size(), "the sibling field is inherited");
        assertEquals("base bye", d.getNode("bye").getText());
        assertNotNull(d.getNode("extra"));

        // Memories merge BY NAME too.
        assertNotNull(d.getMemory("met"), "the parent's declaration survives");
        assertTrue(d.getMemory("met").isShared());
        assertNotNull(d.getMemory("helped"));

        // Start is ONE ordered ladder, so a child that writes one replaces it rather than appending
        // to it - otherwise a child could never take a parent's first greeting out of the running.
        assertEquals(1, d.getStart().first().size());
        assertEquals("greet", d.getStart().first().get(0).getNode());
    }

    @Test
    void aChildThatMentionsNoConversationFieldInheritsTheWholeConversation() {
        engine();
        ZcDialogueAsset base = read("base", """
                { "Abstract": true,
                  "Start": { "First": [ { "Node": "greet" } ] },
                  "Nodes": { "greet": { "Text": "base greet", "Options": [] } } }
                """);
        ZcDialogueAsset child = readWithParent("kid", "{ \"Name\": \"Kid\" }", base);

        NpcDialogue d = child.getDialogue();
        assertNotNull(d, "the parent's conversation carries down whole");
        assertEquals("base greet", d.getNode("greet").getText());
        assertEquals("greet", d.getStart().first().get(0).getNode());
        assertFalse(child.isAbstract(), "a child of a skeleton is a real conversation");
    }

    // ==================== shared option groups as files ====================

    @Test
    void aSharedFragmentFileResolvesThroughIncludeOptions() {
        engine();
        shareFile("footer", """
                { "Options": [ { "LabelKey": "shared.bye", "Close": true } ] }
                """);

        ZcDialogueAsset asset = read("guide", """
                { "Start": { "First": [ { "Node": "greet" } ] },
                  "Nodes": { "greet": { "Options": [ { "LabelKey": "hello" } ],
                                        "IncludeOptions": [ "footer" ] } } }
                """);

        NpcDialogue d = asset.getDialogue();
        assertNotNull(d);
        List<DialogueOption> options = d.getNode("greet").getOptions();
        assertEquals(2, options.size(), "the file's lines are appended after the screen's own");
        assertEquals("hello", options.get(0).getLabelKey());
        assertEquals("shared.bye", options.get(1).getLabelKey());
        assertTrue(options.get(1).closesDialogue());
    }

    @Test
    void theFileNamesCapitalisationNeverHasToMatchTheScreensSpelling() {
        engine();
        shareFile("Farewell", "{ \"Options\": [ { \"LabelKey\": \"shared.bye\" } ] }");

        NpcDialogue d = read("guide", """
                { "Start": { "First": [ { "Node": "greet" } ] },
                  "Nodes": { "greet": { "Options": [], "IncludeOptions": [ "farewell" ] } } }
                """).getDialogue();
        assertNotNull(d);
        assertEquals("shared.bye", d.getNode("greet").getOptions().get(0).getLabelKey());
    }

    /**
     * THE DOUBLE-SPLICE GUARD. A screen a child does not restate is the parent's OWN screen object,
     * already carrying the shared lines from when the parent was read - so a second splice would show
     * the footer twice in the child AND change what the parent conversation says, from a file that
     * mentions neither.
     */
    @Test
    void aScreenInheritedWholeKeepsItsSharedLinesExactlyOnce() {
        engine();
        ZcDialogueAsset base = read("base", """
                { "Abstract": true,
                  "Fragments": { "footer": [ { "LabelKey": "bye" } ] },
                  "Start": { "Fallback": "greet" },
                  "Nodes": { "greet": { "Text": "hello",
                                        "Options": [ { "LabelKey": "own" } ],
                                        "IncludeOptions": [ "footer" ] } } }
                """);
        NpcDialogue parent = base.getDialogue();
        assertNotNull(parent);
        assertEquals(2, parent.getNode("greet").getOptions().size());

        ZcDialogueAsset child = readWithParent("kid", "{ \"Name\": \"Kid\" }", base);
        NpcDialogue kid = child.getDialogue();
        assertNotNull(kid);

        assertEquals(2, kid.getNode("greet").getOptions().size(),
                "the child inherits the screen, not a second copy of the shared lines");
        assertEquals(2, parent.getNode("greet").getOptions().size(),
                "and reading the child leaves the conversation it inherited from untouched");
    }

    /** A child that redeclares a shared group gets ITS version, and the parent keeps the parent's. */
    @Test
    void aChildsOwnVersionOfASharedGroupReplacesTheOneItInherited() {
        engine();
        ZcDialogueAsset base = read("base", """
                { "Abstract": true,
                  "Fragments": { "footer": [ { "LabelKey": "base.bye" } ] },
                  "Start": { "Fallback": "greet" },
                  "Nodes": { "greet": { "Options": [], "IncludeOptions": [ "footer" ] } } }
                """);
        NpcDialogue parent = base.getDialogue();
        assertNotNull(parent);

        NpcDialogue kid = readWithParent("kid", """
                { "Fragments": { "footer": [ { "LabelKey": "kid.bye" } ] } }
                """, base).getDialogue();
        assertNotNull(kid);

        assertEquals(1, kid.getNode("greet").getOptions().size());
        assertEquals("kid.bye", kid.getNode("greet").getOptions().get(0).getLabelKey());
        assertEquals("base.bye", parent.getNode("greet").getOptions().get(0).getLabelKey(),
                "the parent's own screen still says what its own file said");
    }

    @Test
    void aConversationsOwnGroupWinsOverASharedFileOfTheSameName() {
        engine();
        shareFile("footer", "{ \"Options\": [ { \"LabelKey\": \"shared.bye\" } ] }");

        NpcDialogue d = read("guide", """
                { "Fragments": { "footer": [ { "LabelKey": "mine.bye" } ] },
                  "Start": { "First": [ { "Node": "greet" } ] },
                  "Nodes": { "greet": { "Options": [], "IncludeOptions": [ "footer" ] } } }
                """).getDialogue();
        assertNotNull(d);
        List<DialogueOption> options = d.getNode("greet").getOptions();
        assertEquals(1, options.size(), "one group is spliced, not both");
        assertEquals("mine.bye", options.get(0).getLabelKey());
    }

    // ==================== the audit ====================

    @Test
    void aNameOnlyASharedFileAnswersIsNotAFinding() {
        DialogueEngine engine = engine();
        shareFile("footer", "{ \"Options\": [ { \"LabelKey\": \"shared.bye\" } ] }");

        NpcDialogue d = read("guide", """
                { "Start": { "First": [ { "Node": "greet" } ] },
                  "Nodes": { "greet": { "Options": [], "IncludeOptions": [ "footer" ] } } }
                """).getDialogue();
        assertNotNull(d);
        assertFalse(DialogueTestSupport.codes(DialogueStructureValidator.validate(d, null, engine))
                        .contains("UNKNOWN_FRAGMENT"),
                "the group exists, it just lives in a file rather than in this conversation");
    }

    @Test
    void aNameNeitherAnswersIsReported() {
        DialogueEngine engine = engine();
        shareFile("footer", "{ \"Options\": [ { \"LabelKey\": \"shared.bye\" } ] }");

        NpcDialogue d = read("guide", """
                { "Start": { "First": [ { "Node": "greet" } ] },
                  "Nodes": { "greet": { "Options": [], "IncludeOptions": [ "typo" ] } } }
                """).getDialogue();
        assertNotNull(d);
        assertTrue(DialogueTestSupport.codes(DialogueStructureValidator.validate(d, null, engine))
                        .contains("UNKNOWN_FRAGMENT"),
                "nothing anywhere provides those lines, so the screen silently loses them");
    }

    // ==================== helpers ====================

    /** Read a conversation file the way the asset store does, id and all. */
    @Nonnull
    private static ZcDialogueAsset read(@Nonnull String id, @Nonnull String json) {
        return readWithParent(id, json, null);
    }

    /** Read a conversation file against an already-read {@code Parent}, the store's own path. */
    @Nonnull
    private static ZcDialogueAsset readWithParent(@Nonnull String id, @Nonnull String json,
                                                  @Nullable ZcDialogueAsset parent) {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ZcDialogueAsset.class, id, null);
        try {
            ZcDialogueAsset asset = ZcDialogueAsset.CODEC.decodeAndInheritJsonAsset(
                    RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
            assertNotNull(asset, "'" + id + "' must decode");
            return asset;
        } catch (Exception e) {
            throw new AssertionError("dialogue '" + id + "' failed to decode: " + e, e);
        }
    }

    /** Install one shared option group, read from the file shape a pack would ship. */
    private static void shareFile(@Nonnull String name, @Nonnull String json) {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(DialogueFragmentAsset.class, name, null);
        try {
            DialogueFragmentAsset asset = DialogueFragmentAsset.CODEC.decodeJsonAsset(
                    RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
            assertNotNull(asset, "fragment '" + name + "' must decode");
            assertNotNull(asset.getOptions(), "fragment '" + name + "' must carry its lines");
            DialogueTestSupport.shareFragments(Map.of(name, asset.getOptions()));
        } catch (Exception e) {
            throw new AssertionError("fragment '" + name + "' failed to decode: " + e, e);
        }
    }
}
