package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.achievement.asset.AchievementMilestoneAsset;
import com.ziggfreed.common.loot.reward.CommandRewardKind;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardKindAsset;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.objectives.title.TitleAsset;
import com.ziggfreed.common.progress.asset.ObjectiveKindAsset;
import com.ziggfreed.common.progress.asset.ObjectiveKindConfig;
import com.ziggfreed.common.progress.asset.ProgressEditorDataSets;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestCategoryAsset;
import com.ziggfreed.common.quest.asset.QuestGeneratorAsset;
import com.ziggfreed.common.reputation.asset.ReputationAsset;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackCategoryAsset;
import com.ziggfreed.common.track.asset.TrackCurveAsset;
import com.ziggfreed.common.track.pick.PickOptionAsset;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.track.pick.TrackPickGeneratorAsset;
import com.ziggfreed.common.track.source.TrackSourceAsset;

/**
 * The authored stores an admin makes in the Asset Editor export a schema set the editor can read: built
 * the way the engine's {@code SchemaGenerator} builds the set it sends (one context, every store a file
 * of its own, the shared groups in {@code common.json}, {@code other.json} left out as the editor leaves
 * it out), and read by {@link EditorSchemaCheck}, which names the shapes that break every type at once.
 * It is read with live vocabularies, so the rows whose {@code Kind} picks their form are type selectors
 * here, as on a server: a reward row's {@code Params} a form with its pickers, a step's {@code Target} and a
 * track source's {@code When.Match} the suggestions its kind file names, and each selector's arm for a kind
 * it does not list.
 */
class EditorSchemaSetTest {

    /** A kind file's kind, as a mod ships one: a typed parameter and a pick list. */
    private static final String FILE_KIND = "Zz_Editor_Test_Coin";

    @BeforeEach
    void liveVocabularies() throws IOException {
        LootRewardKinds.registerInto(RewardKinds.shared());
        RewardKinds.shared().register(FILE_KIND, "test", new CommandRewardKind(decode(RewardKindAsset.CODEC,
                RewardKindAsset.class, FILE_KIND, """
                        { "Name": "Test coins",
                          "Params": { "Amount": { "Required": true, "Type": "Integer", "Description": "How many." },
                                      "Wallet": { "Pick": { "List": "ziggfreedcommon:currencies" } } },
                          "Command": "give {player} coin {Amount}" }
                        """)));
        ObjectiveKindConfig.getInstance().loadDefaults(Map.of("Kill_Entity", decode(ObjectiveKindAsset.CODEC,
                ObjectiveKindAsset.class, "Kill_Entity", shipped("Kill_Entity"))));
    }

    @AfterEach
    void forgetTheKindFiles() {
        ObjectiveKindConfig.getInstance().loadDefaults(Map.of());
    }

    @Test
    void theAuthoredStoresExportASchemaSetTheEditorCanRead() {
        List<EditorSchemaCheck.Problem> problems = EditorSchemaCheck.check(exportedTheEnginesWay());
        assertEquals(List.of(), problems.stream().map(EditorSchemaCheck.Problem::toString).toList());
    }

    @Test
    void aRewardRowsKindPicksItsParameterForm() {
        BsonDocument definitions = definitions();
        BsonDocument item = definitions.getDocument("RewardEntryAsset@Item");
        assertEquals("Item", property(item, "Kind").getString("const").getValue(), "the arm fixes its Kind");
        BsonDocument params = property(item, "Params");
        assertEquals("Item", property(params, "Item").getString("hytaleAssetRef").getValue(),
                "an Item reward's Item offers the editor's own item picker");
        assertTrue(params.getArray("required").contains(new BsonString("Item")),
                "a parameter the kind requires is marked so");

        BsonDocument file = property(definitions.getDocument("RewardEntryAsset@" + FILE_KIND), "Params");
        assertTrue(typesOf(property(file, "Amount")).contains("integer"), "a kind file's Type picks the box");
        assertTrue(property(file, "Wallet").toJson().contains("ziggfreedcommon:currencies"),
                "a kind file's Pick offers its list");

        BsonDocument rows = definitions.getDocument("ContentRewardsAsset");
        BsonDocument selector = itemsOf(property(rows, "Claim"));
        BsonDocument typeField = selector.getDocument("hytaleSchemaTypeField");
        assertEquals("Kind", typeField.getString("property").getValue(), "the row's Kind picks its form");
        assertTrue(typeField.getArray("values").contains(new BsonString("Item")), typeField.toJson());
    }

    @Test
    void aStepsKindPicksItsTargetSuggestions() {
        BsonDocument kill = definitions().getDocument("QuestObjectiveAsset@KILL_ENTITY");
        assertNotNull(kill, "a quest step offers the kill kind");
        assertSuggests(property(kill, "Target"), "ziggfreedcommon:ids/NPCRole",
                "a kill step's Target suggests the NPC roles its kind file names, a pattern still typeable");
        assertSuggests(itemsOf(property(kill, "Targets")), "ziggfreedcommon:ids/NPCRole", "and so does each Targets entry");
    }

    @Test
    void aMomentTablesKindPicksItsMatchSuggestions() {
        BsonDocument kill = definitions().getDocument("When@KILL_ENTITY");
        assertNotNull(kill, "a track source's When offers the kill kind");
        assertSuggests(property(kill, "Match"), "ziggfreedcommon:ids/NPCRole",
                "its Match suggests the NPC roles, every pattern and set key still typeable");
    }

    @Test
    void everySelectorAdvertisesTheArmForAKindItDoesNotList() {
        BsonDocument definitions = definitions();
        Map<String, String> listedKind = Map.of("RewardEntryAsset", "Item", "QuestObjectiveAsset", "KILL_ENTITY",
                "ObjectiveLeafAsset", "KILL_ENTITY", "When", "KILL_ENTITY");
        for (Map.Entry<String, String> row : listedKind.entrySet()) {
            assertTrue(definitions.containsKey(row.getKey() + "@@other"),
                    row.getKey() + " advertises the arm a kind it does not list decodes into");
            String kind = property(definitions.getDocument(row.getKey() + "@@other"), "Kind").toJson();
            assertTrue(kind.contains("\"allOf\"") && kind.contains("\"not\"") && kind.contains(row.getValue()),
                    row.getKey() + "'s other arm takes any Kind but the listed ones: " + kind);
        }
        BsonDocument rows = definitions.getDocument("ContentRewardsAsset");
        String selector = itemsOf(property(rows, "Claim")).toJson();
        assertTrue(selector.contains("RewardEntryAsset@@other"), "the reward row's union names its other arm");
    }

    /** {@code leaf} offers {@code dataSet} as suggestions on a text box, and no strict picker. */
    private static void assertSuggests(@Nonnull BsonDocument leaf, @Nonnull String dataSet, @Nonnull String why) {
        String json = leaf.toJson();
        assertTrue(json.contains(dataSet) && !leaf.containsKey("hytaleAssetRef"), why + ": " + json);
    }

    @Test
    void theKindListsHoldTheLibrarysOwnVocabularies() {
        assertTrue(ProgressEditorDataSets.objectiveKindIds().contains("KILL_ENTITY"),
                "the shared objective vocabulary is listed with nobody advertising it");
        assertTrue(ProgressEditorDataSets.rewardKindIds().contains("Item"),
                "the shared reward vocabulary is listed, spelled as its owner writes it");
    }

    // ==================== the set, as the engine makes it ====================

    /** The stores in game authoring writes, with the kind files whose vocabularies they pick from. */
    @Nonnull
    private static Map<Class<?>, BuilderCodec<?>> stores() {
        Map<Class<?>, BuilderCodec<?>> out = new LinkedHashMap<>();
        out.put(TrackAsset.class, TrackAsset.CODEC);
        out.put(TrackCategoryAsset.class, TrackCategoryAsset.CODEC);
        out.put(TrackCurveAsset.class, TrackCurveAsset.CODEC);
        out.put(TrackPickAsset.class, TrackPickAsset.CODEC);
        out.put(PickOptionAsset.class, PickOptionAsset.CODEC);
        out.put(TrackPickGeneratorAsset.class, TrackPickGeneratorAsset.CODEC);
        out.put(TrackSourceAsset.class, TrackSourceAsset.CODEC);
        out.put(ReputationAsset.class, ReputationAsset.CODEC);
        out.put(QuestAsset.class, QuestAsset.CODEC);
        out.put(QuestCategoryAsset.class, QuestCategoryAsset.CODEC);
        out.put(QuestGeneratorAsset.class, QuestGeneratorAsset.CODEC);
        out.put(AchievementAsset.class, AchievementAsset.CODEC);
        out.put(AchievementCategoryAsset.class, AchievementCategoryAsset.CODEC);
        out.put(AchievementMilestoneAsset.class, AchievementMilestoneAsset.CODEC);
        out.put(TitleAsset.class, TitleAsset.CODEC);
        out.put(ObjectiveKindAsset.class, ObjectiveKindAsset.CODEC);
        out.put(RewardKindAsset.class, RewardKindAsset.CODEC);
        return out;
    }

    /** The set as the engine builds it for the editor, these stores alone. */
    @Nonnull
    private static Map<String, Schema> exportedTheEnginesWay() {
        Map<Class<?>, BuilderCodec<?>> stores = stores();
        SchemaContext context = new SchemaContext();
        for (Map.Entry<Class<?>, BuilderCodec<?>> store : stores.entrySet()) {
            context.addFileReference(store.getKey().getSimpleName() + ".json", store.getValue());
        }
        Map<String, Schema> set = new LinkedHashMap<>();
        for (Map.Entry<Class<?>, BuilderCodec<?>> store : stores.entrySet()) {
            String name = store.getKey().getSimpleName() + ".json";
            Schema schema = store.getValue().toSchema(context);
            schema.setId(name);
            set.put(name, schema);
        }
        Schema common = new Schema();
        common.setDefinitions(context.getDefinitions());
        common.setId(EditorSchemaCheck.COMMON);
        set.put(EditorSchemaCheck.COMMON, common);
        return set;
    }

    /** {@code common.json}'s definitions, as the editor receives them. */
    @Nonnull
    private static BsonDocument definitions() {
        Schema common = exportedTheEnginesWay().get(EditorSchemaCheck.COMMON);
        return Schema.CODEC.encode(common, new ExtraInfo()).asDocument().getDocument("definitions");
    }

    @Nonnull
    private static BsonDocument property(@Nonnull BsonDocument object, @Nonnull String key) {
        BsonDocument properties = object.getDocument("properties");
        assertTrue(properties.containsKey(key), "no " + key + " in " + properties.keySet());
        return properties.getDocument(key);
    }

    /** An array leaf's item schema, through a nullable union where the leaf is one. */
    @Nonnull
    private static BsonDocument itemsOf(@Nonnull BsonDocument leaf) {
        if (leaf.containsKey("items")) {
            return leaf.getDocument("items");
        }
        for (BsonValue arm : leaf.getArray("anyOf", new BsonArray())) {
            if (arm.isDocument() && arm.asDocument().containsKey("items")) {
                return arm.asDocument().getDocument("items");
            }
        }
        throw new AssertionError("no items in " + leaf.toJson());
    }

    @Nonnull
    private static List<String> typesOf(@Nonnull BsonDocument leaf) {
        BsonValue type = leaf.get("type");
        if (type == null) {
            return List.of();
        }
        return type.isString() ? List.of(type.asString().getValue())
                : type.asArray().stream().map(v -> v.asString().getValue()).toList();
    }

    // ==================== fixtures ====================

    @Nonnull
    private static String shipped(@Nonnull String kindFile) throws IOException {
        String path = "/Server/ZiggfreedCommon/ObjectiveKinds/" + kindFile + ".json";
        try (InputStream in = EditorSchemaSetTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "the library ships " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nonnull
    private static <T extends JsonAsset<String>> T decode(@Nonnull AssetBuilderCodec<String, T> codec,
            @Nonnull Class<T> type, @Nonnull String id, @Nonnull String json) throws IOException {
        T asset = codec.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(type, id, null)));
        assertNotNull(asset, id + " decodes");
        return asset;
    }
}
