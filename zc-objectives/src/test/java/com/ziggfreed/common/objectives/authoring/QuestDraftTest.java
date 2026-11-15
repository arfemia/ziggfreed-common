package com.ziggfreed.common.objectives.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestDefinition;

/**
 * The quest editor's file, a structural gate: what {@link QuestDraft} writes decodes in the quest store as the quest
 * the editor shows (its steps under their keys, every reward a Claim), and writing an edit over a saved file keeps
 * every step's key, the file's order and every leaf the editor has no row for, since a step's key is what a player's
 * progress on it is saved under. A step whose kind changed, or one emptied and added again, must never take an old
 * key, or a player's progress on the old step lands on it.
 */
class QuestDraftTest {

    @Test
    void aWrittenQuestDecodesInTheQuestStore() throws IOException {
        QuestForm form = new QuestForm("server.quests.Skeleton_Trouble.title", null, "guide", false, true, true,
                List.of(new QuestForm.Step("kill_skeleton", "KILL_ENTITY", "Skeleton", 5L, null, null, null)), List.of(),
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
        steps.set(0, new QuestForm.Step("collect", "PICKUP_ITEM", "Copper_Ore", 20L, null, null, null));
        String added = QuestDraft.stepKey("Talk to the miller", loaded.stepKeys()::contains);
        steps.add(new QuestForm.Step(added, "TALK_TO_NPC", "miller", 1L, null, null, null));
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

    @Test
    void aChangedKindTakesANewKeyInTheOldOnesPlace() throws IOException {
        JsonObject saved = copperRun();
        QuestDraft.Loaded loaded = QuestDraft.read(saved, QuestWizard.STEP_SLOTS, QuestWizard.REWARD_SLOTS);
        Set<String> taken = QuestDraft.taken(loaded);

        assertEquals("collect", QuestDraft.keyFor("collect", "PICKUP_ITEM", "PICKUP_ITEM", "Pick up copper",
                taken::contains), "a new count, target or line keeps the step's key");
        String key = QuestDraft.keyFor("collect", "PICKUP_ITEM", "BREAK_BLOCK", "Break copper", taken::contains);
        assertNotEquals("collect", key, "a step of another kind must never carry the old step's progress");

        List<QuestForm.Step> steps = List.of(
                new QuestForm.Step(key, "BREAK_BLOCK", "Copper_Ore", 10L, null, null, "collect"),
                loaded.steps().get(1));
        JsonObject edited = QuestDraft.write(form(loaded, steps, List.of("collect")), saved);

        assertEquals(List.of(key, "hand_in"), new ArrayList<>(edited.getAsJsonObject("Objectives").keySet()),
                "the new step takes the old one's place, so the steps still run in the same order");
        assertEquals(List.of("collect"), QuestDraft.read(edited, QuestWizard.STEP_SLOTS, QuestWizard.REWARD_SLOTS)
                .retiredSteps(), "the old key is retired in the file itself");
        QuestDefinition definition = decode(edited, "Copper_Run").toDefinition(null);
        assertNull(definition.quest().objective("collect"));
        assertEquals("BREAK_BLOCK", definition.quest().objective(key).kind());
    }

    @Test
    void aChangedKindKeepsTheStepsPlaceInTheOrder() throws IOException {
        JsonObject saved = JsonParser.parseString("""
                { "Text": { "TitleKey": "server.quests.Ore_Run.title" },
                  "Objectives": {
                    "gather": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 10, "Order": 1 },
                    "deliver": { "Kind": "TURN_IN", "Target": "Copper_Ore", "Amount": 10, "Order": 2 } } }
                """).getAsJsonObject();
        QuestDraft.Loaded loaded = QuestDraft.read(saved, QuestWizard.STEP_SLOTS, QuestWizard.REWARD_SLOTS);
        String key = QuestDraft.keyFor("gather", "PICKUP_ITEM", "BREAK_BLOCK", "Mine copper",
                QuestDraft.taken(loaded)::contains);

        List<QuestForm.Step> steps = List.of(
                new QuestForm.Step(key, "BREAK_BLOCK", "Copper_Ore", 10L, null, null, "gather"),
                loaded.steps().get(1));
        JsonObject edited = QuestDraft.write(form(loaded, steps, List.of("gather")), saved);

        assertEquals(List.of(key, "deliver"), new ArrayList<>(edited.getAsJsonObject("Objectives").keySet()));
        Quest quest = decode(edited, "Ore_Run").toDefinition(null).quest();
        ObjectiveDef first = quest.objective(key);
        ObjectiveDef after = quest.objective("deliver");
        assertEquals(1, first.order(), "the new step keeps the Order of the step it replaces");
        assertTrue(after.order() > first.order(), "the step that came after the old one comes after the new key");
    }

    @Test
    void aDroppedKeyIsNeverMintedAgain() throws IOException {
        JsonObject saved = JsonParser.parseString("""
                { "Text": { "TitleKey": "server.quests.Bone_Yard.title" },
                  "Objectives": {
                    "kill_skeletons": { "Kind": "KILL_ENTITY", "Target": "Skeleton", "Amount": 5 },
                    "hand_in": { "Kind": "TURN_IN", "Amount": 1 } } }
                """).getAsJsonObject();
        QuestDraft.Loaded loaded = QuestDraft.read(saved, QuestWizard.STEP_SLOTS, QuestWizard.REWARD_SLOTS);

        JsonObject emptied = QuestDraft.write(form(loaded, List.of(loaded.steps().get(1)), List.of("kill_skeletons")),
                saved);
        assertFalse(emptied.getAsJsonObject("Objectives").has("kill_skeletons"));

        QuestDraft.Loaded reopened = QuestDraft.read(emptied, QuestWizard.STEP_SLOTS, QuestWizard.REWARD_SLOTS);
        String key = QuestDraft.keyFor(null, null, "KILL_ENTITY", "Kill skeletons", QuestDraft.taken(reopened)::contains);
        assertNotEquals("kill_skeletons", key, "the same words added again must never land on the old progress");

        List<QuestForm.Step> steps = new ArrayList<>(reopened.steps());
        steps.add(new QuestForm.Step(key, "KILL_ENTITY", "Skeleton", 5L, null, null, null));
        JsonObject readded = QuestDraft.write(form(reopened, steps, List.of()), emptied);

        assertEquals(List.of("kill_skeletons"), QuestDraft.read(readded, QuestWizard.STEP_SLOTS,
                QuestWizard.REWARD_SLOTS).retiredSteps(), "a retired key stays retired");
        QuestDefinition definition = decode(readded, "Bone_Yard").toDefinition(null);
        assertNull(definition.quest().objective("kill_skeletons"));
        assertNotNull(definition.quest().objective(key));
    }

    private static JsonObject copperRun() {
        return JsonParser.parseString("""
                { "Text": { "TitleKey": "server.quests.Copper_Run.title" },
                  "Objectives": {
                    "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 10 },
                    "hand_in": { "Kind": "TURN_IN", "Target": "Copper_Ore", "Amount": 10, "Order": 2 } } }
                """).getAsJsonObject();
    }

    /** The file {@code loaded} came from, written back with {@code steps}, the keys in {@code dropped} emptied. */
    private static QuestForm form(QuestDraft.Loaded loaded, List<QuestForm.Step> steps, List<String> dropped) {
        return new QuestForm(loaded.titleKey(), null, loaded.giver(), false, false, false, steps, dropped,
                QuestWizard.REWARD_SLOTS, loaded.rewards(), loaded.requires(), loaded.repeat());
    }

    private static QuestAsset decode(JsonObject doc, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, id, null);
        return QuestAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(doc.toString()), null,
                new AssetExtraInfo<>(data));
    }
}
