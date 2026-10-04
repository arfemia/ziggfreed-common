package com.ziggfreed.common.dialogue.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueTestSupport;
import com.ziggfreed.common.dialogue.schema.DialogueExtension;
import com.ziggfreed.common.dialogue.schema.DialogueOption;

/**
 * A {@code DialogueExtensions} file read the way the asset store reads it: its id from the file
 * name, folded; its selectors and lines; {@code Enabled}; and a schema the Asset Editor can load.
 */
class DialogueExtensionAssetTest {

    @BeforeEach
    void vocabulary() {
        DialogueTestSupport.reset();
        DialogueEngine.builder().warn(m -> { }).build();
    }

    @Nonnull
    private static DialogueExtensionAsset decode(@Nonnull String id, @Nonnull String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(DialogueExtensionAsset.class, id, null);
        DialogueExtensionAsset asset = DialogueExtensionAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
        assertNotNull(asset, "'" + id + "' must decode");
        return asset;
    }

    @Test
    void aFileReadsItsSelectorsAndLinesUnderItsFoldedId() throws IOException {
        DialogueExtensionAsset asset = decode("Hallows_Eve_Trick_Or_Treat", """
                { "Dialogues": { "Exclude": ["Kweebec_Lobby"] },
                  "On": { "Tags": ["Greeting"] },
                  "Options": [ { "LabelKey": "hallows_eve.trick", "OnceId": "treat",
                                 "Once": { "Period": "Daily" }, "Close": true } ] }
                """);
        assertEquals("hallows_eve_trick_or_treat", asset.getId());
        assertTrue(asset.isEnabled(), "unauthored means in circulation");
        DialogueExtension extension = asset.toExtension(asset.getId());
        assertEquals(List.of("Kweebec_Lobby"), extension.getDialogues().getExclude());
        assertEquals(List.of("Greeting"), extension.getOn().getTags());
        DialogueOption line = extension.getOptions().get(0);
        assertEquals("hallows_eve_trick_or_treat", line.getInjectedBy());
        assertTrue(line.closesDialogue(), "a line's shorthand reads exactly as it does on a screen");
    }

    @Test
    void enabledFalseKeepsTheFileButLandsNowhere() throws IOException {
        DialogueExtension off = decode("Trick", """
                { "Enabled": false, "Options": [ { "LabelKey": "t" } ] }
                """).toExtension("trick");
        assertFalse(off.isEnabled());
        assertFalse(off.lands("old_jack", "menu", List.of(), true));
    }

    @Test
    void theSchemaDeclaresEveryLeafForTheAssetEditor() {
        ObjectSchema schema = DialogueExtensionAsset.CODEC.toSchema(new SchemaContext());
        Map<String, Schema> leaves = schema.getProperties();
        assertTrue(leaves.keySet().containsAll(List.of("Enabled", "Dialogues", "On", "Options")),
                leaves.keySet().toString());
        assertEquals(Boolean.TRUE, ((BooleanSchema) leaves.get("Enabled")).getDefault(),
                "the editor shows the effective unauthored value");
        assertNotNull(((ArraySchema) leaves.get("Options")).getItems(), "the lines array says what it holds");
    }
}
