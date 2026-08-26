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
 * The owner file sits over the shared store the way every owner file in {@code
 * mods/ziggfreedcommon/} does: a bare map from id to the leaves that id should read differently,
 * {@code $}-prefixed keys reserved, and an entry folded over the stored conversation LEAF BY LEAF -
 * the same per-node merge {@code Parent} inheritance uses - so retuning one line does not silently
 * discard every screen the author did not restate.
 */
class DialogueOwnerLayerTest {

    private static final String TWO_NODES = """
            { "Start": { "Fallback": "n" },
              "Nodes": { "n": { "Text": "stored" },
                         "m": { "Text": "second screen" } } }
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
    void aPartialEntryMergesPerNodeOverTheStoredConversation(@TempDir Path dir) throws IOException {
        store("guide_intro", TWO_NODES);
        assertEquals("stored", nodeText("guide_intro", "n"), "the stored conversation answers on its own");

        ownerFile(dir, """
                { "guide_intro": { "Nodes": { "n": { "Text": "owner" } } } }
                """);

        assertEquals("owner", nodeText("guide_intro", "n"), "the restated node reads the owner's way");
        assertEquals("second screen", nodeText("guide_intro", "m"),
                "a screen the owner did not restate keeps what the stored conversation gave it");
    }

    @Test
    void anIdNothingStoresIsANewConversationStandingAlone(@TempDir Path dir) throws IOException {
        ownerFile(dir, """
                { "owner_only": { "Start": { "Fallback": "n" },
                                  "Nodes": { "n": { "Text": "mine" } } } }
                """);

        assertEquals("mine", nodeText("owner_only", "n"),
                "an id no pack defines is the owner's own conversation rather than an error");
    }

    @Test
    void schemaVersionIsAReservedMarkerNotAConversation(@TempDir Path dir) throws IOException {
        store("guide_intro", TWO_NODES);
        ownerFile(dir, """
                { "$SchemaVersion": 1,
                  "$Comment": "retune the greeting",
                  "guide_intro": { "Nodes": { "n": { "Text": "owner" } } } }
                """);

        assertEquals("owner", nodeText("guide_intro", "n"), "the entry beside the marker is in force");
        assertNull(DialogueAssetStore.getInstance().dialogue("$SchemaVersion"),
                "no phantom conversation was made of the marker");
    }

    @Test
    void aNewerSchemaVersionRefusesTheWholeFile(@TempDir Path dir) throws IOException {
        store("guide_intro", TWO_NODES);
        ownerFile(dir, """
                { "$SchemaVersion": 2, "guide_intro": { "Nodes": { "n": { "Text": "owner" } } } }
                """);

        assertEquals("stored", nodeText("guide_intro", "n"),
                "nothing in a future-shaped file is in force; the stored conversation stands");
    }

    @Test
    void anIdIsFoundHoweverItWasCased(@TempDir Path dir) throws IOException {
        store("guide_intro", TWO_NODES);
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

    private static String nodeText(String id, String node) {
        return DialogueAssetStore.getInstance().dialogue(id).getNode(node).getText();
    }
}
