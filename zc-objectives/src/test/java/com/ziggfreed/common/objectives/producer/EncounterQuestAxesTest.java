package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.quest.asset.QuestEnumeratorRegistry;
import com.ziggfreed.common.quest.asset.QuestGeneratorExpander;

/**
 * The encounters axis, read through the same value source a generator is expanded with: every
 * enabled binding, its name key beside it, narrowed by the one filter, and switched off rows left
 * out.
 */
class EncounterQuestAxesTest {

    private static final String TOKEN = "boss";

    @AfterEach
    void reset() {
        EncounterBindingConfig.getInstance().loadDefaults(Map.of());
    }

    @Nonnull
    private static EncounterBindingAsset binding(@Nonnull String id, @Nonnull String json) throws IOException {
        EncounterBindingAsset asset = EncounterBindingAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(EncounterBindingAsset.class, id, null)));
        assertNotNull(asset, "the fixture decodes");
        return asset;
    }

    @Nonnull
    private static List<Map<String, JsonPrimitive>> rows(@Nonnull Map<String, String> filter) throws Exception {
        QuestEnumeratorRegistry registry = new QuestEnumeratorRegistry("test-axis");
        EncounterQuestAxes.install(registry, "test");
        return QuestGeneratorExpander.axisValues(registry).rows(EncounterQuestAxes.SOURCE_ENCOUNTERS, TOKEN, filter);
    }

    private void bind(@Nonnull EncounterBindingAsset... rows) {
        Map<String, EncounterBindingAsset> byId = new java.util.LinkedHashMap<>();
        for (EncounterBindingAsset row : rows) {
            byId.put(row.getId(), row);
        }
        EncounterBindingConfig.getInstance().loadDefaults(byId);
    }

    @Test
    void everyEnabledBindingIsOneRowBindingTheScriptIdAndItsNameKey() throws Exception {
        bind(binding("Warden", """
                        { "EncounterAsset": "Kweebec_Warden", "NameKey": "kweebecnightmare.npc.warden.name" }
                        """),
                binding("Zc_Encounter_Example", """
                        { "NameKey": "ziggfreedcommon.encounter.example.name" }
                        """));

        List<Map<String, JsonPrimitive>> rows = rows(Map.of());

        assertEquals(2, rows.size());
        assertEquals("Kweebec_Warden", rows.get(0).get(TOKEN).getAsString(),
                "the axis's own token is the SCRIPT id a step targets, not the row's file name");
        assertEquals("kweebecnightmare.npc.warden.name", rows.get(0).get(EncounterQuestAxes.TOKEN_NAME_KEY).getAsString());
        assertEquals("Zc_Encounter_Example", rows.get(1).get(TOKEN).getAsString(),
                "a row naming no script binds its own id, and the list is sorted by it");
    }

    @Test
    void aBindingSwitchedOffIsNotListed() throws Exception {
        bind(binding("On", "{ }"), binding("Off", """
                { "Enabled": false }
                """));

        List<Map<String, JsonPrimitive>> rows = rows(Map.of());

        assertEquals(1, rows.size(), "a boss taken out of rotation gets no generated contract");
        assertEquals("On", rows.get(0).get(TOKEN).getAsString());
    }

    @Test
    void theDifficultyFilterNarrowsToRowsAuthoredWithThatLabel() throws Exception {
        bind(binding("Easy", """
                        { "Progression": { "Difficulty": "normal" } }
                        """),
                binding("Hard", """
                        { "Progression": { "Difficulty": "Hard" } }
                        """),
                binding("Unlabelled", "{ }"));

        List<Map<String, JsonPrimitive>> rows = rows(Map.of("Difficulty", "hard"));

        assertEquals(1, rows.size(), "an unlabelled row is not a hard one");
        assertEquals("Hard", rows.get(0).get(TOKEN).getAsString());
        assertTrue(rows(Map.of("Difficulty", "nightmare")).isEmpty(),
                "a label nobody authored answers nothing rather than everything");
    }

    @Test
    void aRowWithNoNameKeyBindsABlankOne() throws Exception {
        bind(binding("Nameless", "{ }"));

        List<Map<String, JsonPrimitive>> rows = rows(Map.of());

        assertEquals("", rows.get(0).get(EncounterQuestAxes.TOKEN_NAME_KEY).getAsString(),
                "a generated title over a blank key falls back rather than dropping the row");
    }
}
