package com.ziggfreed.common.dialogue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;
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

    /**
     * Forget every registered type AND every shared option group, so this test's engine is the only
     * vocabulary in play and the only groups a conversation can splice are the ones it installs
     * itself. Both are process-wide (one asset store, one chance to read a file), so a test that
     * skipped this would inherit whatever the previous test class left behind.
     */
    static void reset() {
        DialogueTypeTable.get().resetForTests();
        DialogueFragmentConfig.getInstance().mergePackLayer(Map.of());
        // The routing vocabulary is process-wide for the same reason the decode one is, and an
        // option's Open is read through it - so a test that authors one starts from a clean table
        // and registers what its own files name.
        Destinations.clearForTests();
        // And what a Start quest row routes THROUGH, installed by the library's own plugin at setup:
        // a test that leaves it behind would decide the next test's ladder.
        DialogueQuestView.install(null);
    }

    /**
     * A destination with no fields of its own, for a test that only needs an {@code Open} value to
     * be READABLE. Opening it does nothing, which is all a decode test can observe anyway.
     *
     * <p>Every one of these decodes into the SAME class, so registering two is fine on the decode
     * side (which dispatches by the authored id) and meaningless on the encode side (which dispatches
     * by class). Do not reach for this to test what a destination DOES.
     */
    static void shareDestination(@Nonnull String typeId) {
        Destinations.register("test", DestinationType.of(typeId, TestDestination.class,
                BuilderCodec.builder(TestDestination.class, TestDestination::new).build(),
                (destination, ctx) -> false));
    }

    /** The stand-in a {@link #shareDestination} type decodes into. */
    static final class TestDestination extends Destination {
    }

    /** Install shared option groups, standing in for the files under {@code DialogueFragments/}. */
    static void shareFragments(@Nonnull Map<String, DialogueOption[]> groups) {
        DialogueFragmentConfig.getInstance().mergePackLayer(groups);
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
