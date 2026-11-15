package com.ziggfreed.common.objectives.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestDefinition;

/**
 * The quest editor's file, a structural gate: what {@link QuestDraft} writes decodes in the quest store as the quest
 * the editor shows (its steps under their keys, every reward a Claim), and writing an edit over a saved file keeps
 * every step's key, the file's order and every leaf the editor has no row for, since a step's key is what a player's
 * progress on it is saved under.
 */
class QuestDraftTest {

    @Test
    void aWrittenQuestDecodesInTheQuestStore() throws IOException {
        QuestForm form = new QuestForm("server.quests.Skeleton_Trouble.title", null, "guide", false, true, true,
                List.of(new QuestForm.Step("kill_skeleton", "KILL_ENTITY", "Skeleton", 5L, null, null)), List.of(),
                QuestWizard.REWARD_SLOTS,
                List.of(new QuestForm.Reward(0, "Item", Map.of("Item", "Ingredient_Bar_Gold", "Count", "5"),
                        Set.of("Item", "Count"), false)),
                new QuestForm.Requirements(null, null, null, null, null), QuestForm.Repeat.ONCE);

        QuestAsset asset = decode(QuestDraft.write(form, null), "Skeleton_Trouble");
        QuestDefinition definition = asset.toDefinition(null);

        ObjectiveDef kill = definition.quest().objective("kill_skeleton");
        assertNotNull(kill, "the step must be filed under the key it was minted with");
        assertEquals("KILL_ENTITY", kill.kind());
        assertEquals(5L, kill.amount());
        assertEquals("guide", definition.npcViewId());
        assertEquals("server.quests.Skeleton_Trouble.title", definition.titleKey());
        assertEquals(1, definition.quest().claimRewards().size(), "the reward waits to be collected");
        assertTrue(definition.quest().autoRewards().isEmpty(), "the editor never writes a reward paid on the spot");
    }

    @Test
    void anEditKeepsEveryStepKey() throws IOException {
        JsonObject saved = JsonParser.parseString("""
                { "Text": { "TitleKey": "server.quests.Copper_Run.title" },
                  "Npc": { "ViewId": "miller" },
                  "Objectives": {
                    "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 10 },
                    "hand_in": { "Kind": "TURN_IN", "Target": "Copper_Ore", "Amount": 10, "Order": 2 } },
                  "Rewards": { "Claim": [ { "Kind": "Item", "Params": { "Item": "Ingredient_Bar_Copper", "Count": "3" } } ] },
                  "Meta": { "yourmod": { "Faction": "wardens" } } }
                """).getAsJsonObject();
        QuestDraft.Loaded loaded = QuestDraft.read(saved, QuestWizard.STEP_SLOTS, QuestWizard.REWARD_SLOTS);

        List<QuestForm.Step> steps = new ArrayList<>(loaded.steps());
        steps.set(0, new QuestForm.Step("collect", "PICKUP_ITEM", "Copper_Ore", 20L, null, null));
        String added = QuestDraft.stepKey("Talk to the miller", loaded.stepKeys()::contains);
        steps.add(new QuestForm.Step(added, "TALK_TO_NPC", "miller", 1L, null, null));
        QuestForm form = new QuestForm(loaded.titleKey(), null, loaded.giver(), false, false, false, steps, List.of(),
                QuestWizard.REWARD_SLOTS, loaded.rewards(), loaded.requires(), loaded.repeat());

        JsonObject edited = QuestDraft.write(form, saved);

        assertEquals(List.of("collect", "hand_in", added),
                new ArrayList<>(edited.getAsJsonObject("Objectives").keySet()),
                "every saved step keeps its key and its place, and a new one goes after them");
        assertEquals(2, edited.getAsJsonObject("Objectives").getAsJsonObject("hand_in").get("Order").getAsInt(),
                "a leaf the editor has no row for stays as it was written");
        assertTrue(edited.has("Meta"), "a namespace the editor has no row for stays as it was written");
        assertFalse(edited.getAsJsonObject("Rewards").has("Auto"));

        QuestDefinition definition = decode(edited, "Copper_Run").toDefinition(null);
        assertEquals(20L, definition.quest().objective("collect").amount());
        assertNotNull(definition.quest().objective("hand_in"));
        assertNotNull(definition.quest().objective(added));
        assertEquals(1, definition.quest().claimRewards().size());
    }

    private static QuestAsset decode(JsonObject doc, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, id, null);
        return QuestAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(doc.toString()), null,
                new AssetExtraInfo<>(data));
    }
}
