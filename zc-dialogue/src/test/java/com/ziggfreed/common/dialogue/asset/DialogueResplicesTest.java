package com.ziggfreed.common.dialogue.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueTestSupport;
import com.ziggfreed.common.dialogue.schema.DialogueExtension;
import com.ziggfreed.common.dialogue.schema.DialogueFragmentGroup;
import com.ziggfreed.common.dialogue.schema.DialogueNode;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;

/**
 * The engine reloads an extension or a shared-group file without reading the conversations again,
 * and the splice runs as a conversation is read; so a reload re-splices everything in circulation,
 * the owner's own conversations included, and never stacks a line doing it.
 */
class DialogueResplicesTest {

    private static final String GUIDE = """
            { "Start": { "Fallback": "menu" },
              "Nodes": { "menu": { "Options": [ { "LabelKey": "a" } ], "IncludeOptions": ["tail"] } } }
            """;

    @BeforeEach
    void vocabulary() {
        DialogueTestSupport.reset();
        DialogueEngine.builder().warn(m -> { }).build();
    }

    /** Every layer is a process-wide singleton, so each is put back EMPTY for the next test. */
    @AfterEach
    void leaveEveryLayerEmpty() {
        DialogueOverrides.getInstance().resetForTests();
        DialogueOverrides.getInstance().setFile(Path.of("mods", "ziggfreedcommon", "dialogues.json"));
        DialogueAssetStore.getInstance().mergeExtensions(Map.of());
        DialogueAssetStore.getInstance().mergeFragments(Map.of());
        DialogueAssetStore.getInstance().merge(Map.of());
    }

    @Test
    void anExtensionLoadedLaterReachesConversationsAlreadyInCirculation() throws IOException {
        store("guide", GUIDE);
        assertEquals(List.of("a"), labels("guide", "menu"));

        DialogueAssetStore.getInstance().mergeExtensions(Map.of("trick", trick()));
        assertEquals(List.of("a", "hallows_eve.trick"), labels("guide", "menu"));

        DialogueAssetStore.getInstance().mergeExtensions(Map.of("trick", trick()));
        assertEquals(List.of("a", "hallows_eve.trick"), labels("guide", "menu"), "a second reload never stacks the line");

        DialogueAssetStore.getInstance().mergeExtensions(Map.of());
        assertEquals(List.of("a"), labels("guide", "menu"), "a removed extension takes its line with it");
    }

    @Test
    void aConversationReadAfterTheExtensionHasTheLineFromItsFirstRead() throws IOException {
        DialogueAssetStore.getInstance().mergeExtensions(Map.of("trick", trick()));
        store("guide", GUIDE);
        assertEquals(List.of("a", "hallows_eve.trick"), labels("guide", "menu"));
    }

    @Test
    void aReloadedSharedGroupReachesTheScreensThatPullItIn() throws IOException {
        store("guide", GUIDE);
        assertEquals(List.of("a"), labels("guide", "menu"), "the group is not loaded yet");
        DialogueAssetStore.getInstance().mergeFragments(Map.of("tail",
                DialogueFragmentGroup.ofOptions(DialogueTestSupport.optionRows("[ { \"LabelKey\": \"t\" } ]"))));
        assertEquals(List.of("a", "t"), labels("guide", "menu"));
    }

    @Test
    void theOwnersOwnConversationsAreSplicedToo(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dialogues.json");
        Files.writeString(file, """
                { "owner_only": { "Start": { "Fallback": "n" },
                                  "Nodes": { "n": { "Options": [ { "LabelKey": "o" } ] } } } }
                """, StandardCharsets.UTF_8);
        DialogueOverrides.getInstance().setFile(file);
        DialogueAssetStore.getInstance().merge(Map.of());
        assertEquals(List.of("o"), labels("owner_only", "n"));

        DialogueAssetStore.getInstance().mergeExtensions(Map.of("trick", trick()));
        assertEquals(List.of("o", "hallows_eve.trick"), labels("owner_only", "n"));
    }

    @Nonnull
    private static DialogueExtension trick() {
        return DialogueExtension.of("trick",
                DialogueTestSupport.optionRows("[ { \"LabelKey\": \"hallows_eve.trick\" } ]"), null, null, true);
    }

    /** Put one conversation into the shared layer the way a load event would, its id set by the codec. */
    private static void store(@Nonnull String id, @Nonnull String body) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ZcDialogueAsset.class, id, null);
        ZcDialogueAsset asset = ZcDialogueAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromJsonString(body), new AssetExtraInfo<>(data));
        assertNotNull(asset, "'" + id + "' must decode");
        DialogueAssetStore.getInstance().merge(Map.of(id, asset));
    }

    @Nonnull
    private static List<String> labels(@Nonnull String id, @Nonnull String node) {
        NpcDialogue dialogue = DialogueAssetStore.getInstance().dialogue(id);
        assertNotNull(dialogue, "'" + id + "' is in circulation");
        DialogueNode screen = dialogue.getNode(node);
        assertNotNull(screen, "'" + id + "' has screen '" + node + "'");
        return screen.getOptions().stream().map(DialogueOption::getLabelKey).toList();
    }
}
