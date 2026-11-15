package com.ziggfreed.common.objectives.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementDefinition;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.title.TitleAsset;
import com.ziggfreed.common.objectives.title.TitleText;

/**
 * The achievement editor's file, a structural gate: what {@link AchievementDraft} writes decodes in the achievement
 * store as the achievement the editor shows (its criteria under their keys, every reward a Claim, its title the one
 * Auto row), and writing an edit over a saved or shipped file keeps every criterion key, every key its words are
 * filed under and every leaf the editor has no row for, since a criterion's key is what a player's progress on it is
 * saved under. A criterion whose kind changed, or one emptied and added again, must never take an old key.
 */
class AchievementDraftTest {

    private static final int CRITERIA = AchievementWizard.CRITERION_SLOTS;
    private static final int REWARDS = AchievementWizard.REWARD_SLOTS;

    @Test
    void aWrittenAchievementDecodesWithItsCriteriaClaimsAndTitle() throws IOException {
        String key = AchievementDraft.criterionKey("Break 100 stone", Set.<String>of()::contains);
        assertEquals("break_100_stone", key);
        AchievementForm form = new AchievementForm("server.achievements.Stone_Breaker.title", null, "gathering",
                null, 20, true, List.of(new KeyedRows.Row(key, "BREAK_BLOCK", "Rock_Stone", 100L, null, null, null)),
                List.of(), REWARDS, List.of(new ClaimSlots.Slot(0, "Item",
                        Map.of("Item", "Ingredient_Bar_Iron", "Count", "5"), Set.of("Item", "Count"), false)),
                "The_Mason");

        AchievementDefinition definition = decode(AchievementDraft.write(form, null), "Stone_Breaker");
        Achievement achievement = definition.achievement();

        int at = achievement.indexOf(key);
        assertTrue(at >= 0, "the criterion is filed under the key it was minted with");
        assertEquals("BREAK_BLOCK", achievement.criterion(at).authoredKind());
        assertEquals(100L, achievement.criterion(at).amount());
        assertEquals(20, achievement.points());
        assertEquals("gathering", definition.category());
        assertEquals("server.achievements.Stone_Breaker.title", definition.titleKey());
        assertEquals(1, achievement.claimRewards().size(), "the reward waits to be collected");
        assertEquals(1, achievement.autoRewards().size(), "the title is the one row that lands on unlock");
        RewardSpec title = achievement.autoRewards().get(0);
        assertEquals("Title", title.kind());
        assertEquals("The_Mason", title.param("Title"));
    }

    @Test
    void noTitleMeansNoAutoRow() throws IOException {
        AchievementForm form = new AchievementForm("server.achievements.Digger.title", null, "gathering", null, 10,
                true, List.of(new KeyedRows.Row("dig", "BREAK_BLOCK", "Soil_Dirt", 10L, null, null, null)), List.of(),
                REWARDS, List.of(), null);

        JsonObject doc = AchievementDraft.write(form, null);

        assertFalse(doc.has("Rewards"), "nothing to pay writes no rewards at all");
        assertTrue(decode(doc, "Digger").achievement().autoRewards().isEmpty());
    }

    @Test
    void anEditKeepsEveryKeyAndEveryLeafTheEditorHasNoRowFor() throws IOException {
        JsonObject saved = JsonParser.parseString("""
                { "Text": { "TitleKey": "server.achievements.Ore_Hand.title" },
                  "Listing": { "Category": "gathering", "Hidden": true },
                  "Requires": { "Permission": "yourmod.miner" },
                  "Criteria": {
                    "copper": { "Kind": "BREAK_BLOCK", "Target": "Ore_Copper", "Amount": 10, "Zone": "Zone1" },
                    "iron": { "Kind": "BREAK_BLOCK", "Target": "Ore_Iron", "Amount": 10 } },
                  "Rewards": {
                    "Auto": [ { "Kind": "yourmod:coins", "Params": { "Amount": "5" } },
                              { "Kind": "Title", "Params": { "Title": "Old_Hand" } } ],
                    "Claim": [ { "Kind": "Item", "Params": { "Item": "Ingredient_Bar_Copper", "Count": "3" } } ] },
                  "Meta": { "yourmod": { "Chain": "ores" } } }
                """).getAsJsonObject();
        AchievementDraft.Loaded loaded = AchievementDraft.read(saved, CRITERIA, REWARDS);
        assertEquals("Old_Hand", loaded.title());

        List<KeyedRows.Row> criteria = new ArrayList<>(loaded.criteria());
        KeyedRows.Row copper = criteria.get(0);
        criteria.set(0, new KeyedRows.Row(copper.key(), copper.kind(), copper.target(), 25L, null, null, null));
        String added = AchievementDraft.criterionKey("Break gold", AchievementDraft.taken(loaded)::contains);
        criteria.add(new KeyedRows.Row(added, "BREAK_BLOCK", "Ore_Gold", 5L, null, null, null));
        JsonObject edited = AchievementDraft.write(form(loaded, criteria, List.of(), "New_Hand"), saved);

        assertEquals(List.of("copper", "iron", added), new ArrayList<>(edited.getAsJsonObject("Criteria").keySet()),
                "every saved criterion keeps its key and its place, and a new one goes after them");
        assertEquals("Zone1", edited.getAsJsonObject("Criteria").getAsJsonObject("copper").get("Zone").getAsString());
        assertEquals(saved.get("Requires"), edited.get("Requires"), "the requirements stay as written");
        assertTrue(edited.getAsJsonObject("Listing").get("Hidden").getAsBoolean());
        assertEquals(saved.getAsJsonObject("Meta").get("yourmod"), edited.getAsJsonObject("Meta").get("yourmod"));

        AchievementDefinition definition = decode(edited, "Ore_Hand");
        Achievement achievement = definition.achievement();
        assertEquals(25L, achievement.criterion(achievement.indexOf("copper")).amount());
        assertEquals(2, achievement.autoRewards().size(), "an Auto row that is no title stays as written");
        assertEquals("yourmod:coins", achievement.autoRewards().get(0).kind());
        assertEquals("New_Hand", achievement.autoRewards().get(1).param("Title"));
        assertEquals(1, achievement.claimRewards().size());
    }

    @Test
    void aChangedKindTakesANewKeyAndRetiresTheOldOne() throws IOException {
        JsonObject saved = JsonParser.parseString("""
                { "Text": { "TitleKey": "server.achievements.Stone_Breaker.title" },
                  "Criteria": { "break_stone": { "Kind": "BREAK_BLOCK", "Target": "Rock_Stone", "Amount": 100 } } }
                """).getAsJsonObject();
        AchievementDraft.Loaded loaded = AchievementDraft.read(saved, CRITERIA, REWARDS);
        Set<String> taken = AchievementDraft.taken(loaded);

        assertEquals("break_stone", AchievementDraft.keyFor("break_stone", "BREAK_BLOCK", "BREAK_BLOCK", "Break stone",
                taken::contains), "a new count, target or line keeps the criterion's key");
        String key = AchievementDraft.keyFor("break_stone", "BREAK_BLOCK", "PLACE_BLOCK", "Break stone",
                taken::contains);
        assertNotEquals("break_stone", key, "a criterion of another kind never carries the old one's progress");

        JsonObject edited = AchievementDraft.write(form(loaded,
                List.of(new KeyedRows.Row(key, "PLACE_BLOCK", "Rock_Stone", 100L, null, null, "break_stone")),
                List.of("break_stone"), null), saved);

        assertEquals(List.of(key), new ArrayList<>(edited.getAsJsonObject("Criteria").keySet()));
        AchievementDraft.Loaded reopened = AchievementDraft.read(edited, CRITERIA, REWARDS);
        assertEquals(List.of("break_stone"), reopened.retiredCriteria(), "the old key is retired in the file itself");
        assertNotEquals("break_stone", AchievementDraft.criterionKey("Break stone",
                AchievementDraft.taken(reopened)::contains), "a retired key is never minted again");
        Achievement achievement = decode(edited, "Stone_Breaker").achievement();
        assertEquals(-1, achievement.indexOf("break_stone"));
        assertEquals("PLACE_BLOCK", achievement.criterion(achievement.indexOf(key)).authoredKind());
    }

    @Test
    void aShippedAchievementOpenedAndSavedKeepsItsOwnTextKeys() throws IOException {
        JsonObject shipped = JsonParser.parseString("""
                { "Name": "Prospector",
                  "Text": { "TitleKey": "yourmod.ach.prospector.title" },
                  "Listing": { "Category": "gathering" },
                  "Criteria": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Ore_Copper", "Amount": 50,
                                          "TextKey": "yourmod.ach.prospector.mine" } } }
                """).getAsJsonObject();
        AchievementDraft.Loaded loaded = AchievementDraft.read(shipped, CRITERIA, REWARDS);
        Map<String, String> texts = new HashMap<>();
        String fresh = "server.achievements.Prospector.title";

        String titleKey = AchievementDraft.keptKey(loaded.titleKey(), "Prospector", "Prospector", key -> false,
                () -> fresh, texts);
        String lineKey = AchievementDraft.keptKey(loaded.criteria().get(0).textKey(), "Mine copper", "Mine copper",
                key -> false, () -> "server.achievements.Prospector.criterion.mine", texts);
        assertEquals("yourmod.ach.prospector.title", titleKey, "words left as they were keep the shipped key");
        assertTrue(texts.isEmpty(), "and save no words of their own");
        assertEquals(fresh, AchievementDraft.keptKey(loaded.titleKey(), "Prospector", "Copper Prospector",
                key -> false, () -> fresh, texts), "retyped words take a server key");
        assertNull(AchievementDraft.keptKey(null, "Prospector", "Prospector", key -> false, () -> fresh,
                new HashMap<>()), "words the convention line names keep no key at all");

        KeyedRows.Row mine = loaded.criteria().get(0);
        JsonObject saved = AchievementDraft.write(new AchievementForm(titleKey, null, loaded.category(), null,
                loaded.points(), true, List.of(new KeyedRows.Row(mine.key(), mine.kind(), mine.target(), 80L, null,
                        lineKey, null)), List.of(), REWARDS, loaded.rewards(), null), shipped);

        AchievementDefinition definition = decode(saved, "Prospector");
        assertEquals("yourmod.ach.prospector.title", definition.titleKey());
        assertEquals("yourmod.ach.prospector.mine", definition.criterionTextKey("mine"));
        assertEquals(80L, definition.achievement().criterion(definition.achievement().indexOf("mine")).amount());
    }

    @Test
    void theTitleFileDecodesAndItsDisplayLineSitsUnderTheConventionKey() throws IOException {
        JsonObject doc = AchievementDraft.titleDocument("server.titles.The_Mason.name", null, null);
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TitleAsset.class, "The_Mason", null);
        TitleAsset title = TitleAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(doc.toString()),
                null, new AssetExtraInfo<>(data));

        assertNotNull(title.text());
        assertEquals("server.titles.The_Mason.name", title.text().getTitleKey());
        assertEquals(TitleText.displayKey("The_Mason"), AchievementDraft.displayKey("The_Mason"),
                "the line placing a title around a name is read only from title.<id>.display");
        assertEquals("{0} the Mason", AchievementDraft.TitlePlace.AFTER.displayLine("the Mason"));
        assertNull(AchievementDraft.TitlePlace.COMMA.displayLine("the Mason"), "after a comma is the shared line");
    }

    /** The file {@code loaded} came from, written back with {@code criteria}, the keys in {@code dropped} let go. */
    private static AchievementForm form(AchievementDraft.Loaded loaded, List<KeyedRows.Row> criteria,
            List<String> dropped, String title) {
        return new AchievementForm(loaded.titleKey(), loaded.flavorKey(), loaded.category(), loaded.subcategory(),
                loaded.points(), loaded.enabled(), criteria, dropped, REWARDS, loaded.rewards(), title);
    }

    private static AchievementDefinition decode(JsonObject doc, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(AchievementAsset.class, id, null);
        return AchievementAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(doc.toString()), null,
                new AssetExtraInfo<>(data)).toDefinition();
    }
}
