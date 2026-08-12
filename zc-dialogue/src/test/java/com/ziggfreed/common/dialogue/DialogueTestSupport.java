package com.ziggfreed.common.dialogue;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.validation.Finding;

/**
 * Shared plumbing for the dialogue tests.
 *
 * <p>The decode vocabulary is process-wide (one asset store, one chance to read a file), so a test
 * that registers its own types has to start from a clean one or it inherits whatever the previous
 * test class taught the table. {@link #reset()} does that, and every test class calls it before each
 * test.
 */
final class DialogueTestSupport {

    private DialogueTestSupport() {
    }

    /** Forget every registered type, so this test's engine is the only vocabulary in play. */
    static void reset() {
        DialogueTypeTable.get().resetForTests();
    }

    /**
     * Read a body against an already-read parent through the SAME path the asset store takes for a
     * file carrying {@code "Parent"} - the codec's own inherit-decode, not a hand-rolled merge.
     */
    @Nullable
    static NpcDialogue decodeWithParent(@Nonnull DialogueEngine engine, @Nonnull String id,
                                        @Nonnull String json, @Nonnull NpcDialogue parent)
            throws Exception {
        NpcDialogue child = engine.dialogueCodec()
                .decodeAndInheritJson(RawJsonReader.fromJsonString(json), parent, new ExtraInfo());
        if (child != null) {
            child.setId(id);
        }
        return child;
    }

    /** The finding codes, for a readable assertion message. */
    @Nonnull
    static List<String> codes(@Nonnull List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }
}
