package com.ziggfreed.common.dialogue.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The owner file sits over the shared store, and lookups do not care how an id was cased.
 *
 * <p>A conversation is authored once, in the folder every mod on the server reads back, and the one
 * thing above that folder is the server owner's own file. An entry there REPLACES the stored
 * conversation of that id outright rather than merging with it, because merging is what
 * {@code Parent} is for and it happens while the asset files are read; a body written in the owner
 * file has no parent and stands on its own.
 */
class DialogueOwnerLayerTest {

    private static final String ONE_NODE = """
            { "Start": { "Fallback": "n" },
              "Nodes": { "n": { "Text": "stored" } } }
            """;

    private static final String OWNER_BODY = """
            { "Start": { "Fallback": "n" },
              "Nodes": { "n": { "Text": "owner" } } }
            """;

    /**
     * Both layers are process-wide singletons, so this puts them back EMPTY rather than merely
     * dropping the override: another test in the same JVM asserts on a server where no conversation
     * has loaded at all, and a leftover entry here would answer that question for it.
     */
    @AfterEach
    void leaveBothLayersEmpty() {
        DialogueOverrides.getInstance().resetForTests();
        DialogueOverrides.getInstance().setFile(Path.of("mods", "ziggfreedcommon", "dialogues.json"));
        DialogueAssetStore.getInstance().merge(Map.of());
    }

    @Test
    void anOwnerEntryReplacesTheStoredConversationOfThatId(@TempDir Path dir) throws IOException {
        store("guide_intro", ONE_NODE);
        assertEquals("stored", nodeText("guide_intro"), "the stored conversation answers on its own");

        ownerFile(dir, """
                { "dialogues": { "guide_intro": %s } }
                """.formatted(OWNER_BODY));
        assertEquals("owner", nodeText("guide_intro"), "the owner's own body replaces the stored one");
    }

    @Test
    void anIdIsFoundHoweverItWasCased(@TempDir Path dir) throws IOException {
        store("guide_intro", ONE_NODE);
        assertNotNull(DialogueAssetStore.getInstance().dialogue("GUIDE_INTRO"),
                "an id is the same id whatever case it was written in");
        assertNull(DialogueAssetStore.getInstance().dialogue("nope"),
                "an id nothing answers for resolves to nothing, which the page says on its screen");
    }

    /** Put one decoded conversation into the shared layer, the way a load event would. */
    private static void store(String id, String body) {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ZcDialogueAsset.class, id, null);
        try {
            ZcDialogueAsset asset = ZcDialogueAsset.CODEC.decodeJson(
                    RawJsonReader.fromJsonString(body), new AssetExtraInfo<>(data));
            assertNotNull(asset, "'" + id + "' must decode");
            DialogueAssetStore.getInstance().merge(Map.of(id, asset));
        } catch (Exception e) {
            throw new AssertionError("dialogue '" + id + "' failed to decode: " + e, e);
        }
    }

    /** Point the owner layer at a written file and fold it in. */
    private static void ownerFile(Path dir, String json) throws IOException {
        Path file = dir.resolve("dialogues.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        DialogueOverrides.getInstance().setFile(file);
        DialogueAssetStore.getInstance().applyOwnerLayer();
    }

    private static String nodeText(String id) {
        return DialogueAssetStore.getInstance().dialogue(id).getNode("n").getText();
    }
}
