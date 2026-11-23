package com.ziggfreed.common.progress.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.quest.asset.QuestGeneratorAsset;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The one expander's rows and second pass (one contract for every generator, and the walk has bitten before): a
 * set of rows times an authored step axis, each step's value naming a member's token, fills from the same
 * combination and keeps its type; tokens filling each other in a loop, or naming nothing, skip the entry with an
 * ERROR; and a move of a set's rows re-expands the store that walked it, and only then.
 */
class GeneratorRowsTest {

    private static final String SET = "Yours_Schools";

    /**
     * Two step rows, each naming the member's own amount and line through a token of its own. A quest generator
     * lower-cases its Base before filling it, so the Base's token is spelled lower-case.
     */
    private static final String STEPS = """
            { "Base": "Yours_Tier_{tier}", "IdPattern": "Yours_{School}_{Step}",
              "ForEach": [ { "Source": "ziggfreedcommon:rows", "Filter": { "Set": "yours_schools" } },
                           { "Values": [ { "Step": "First",  "tier": "I",  "Amount": "{First}" },
                                         { "Step": "Second", "tier": "II", "Amount": "{Second}" } ] } ],
              "Child": { "Grants": [ { "Stat": "{Line}", "Add": "{Amount}" } ] } }
            """;

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        GeneratorRows.forget();
        GeneratorRowConfig.setDirectory(OwnerFiles.DIRECTORY);
        GeneratorRowConfig.getInstance().mergeOwnerLayer(Map.of());
        GeneratorRowConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void aSetOfRowsTimesAStepAxisFillsEachStepFromItsOwnMember() throws IOException {
        packRows();

        GeneratorCore.Expansion expansion = GeneratorCore.expand(generator(STEPS), "test", "entry", null);

        assertEquals(List.of(), expansion.issues(), "the walk is clean, and a member's token no step reads is "
                + "never called unused: the set is shared by every generator walking it");
        assertEquals(List.of("yours_ice_first", "yours_ice_second", "yours_fire_first", "yours_fire_second"),
                expansion.bodies().stream().map(GeneratedBody::id).toList(),
                "a switched-off row is out, the rest in Order, and the first axis varies slowest");
        JsonObject grant = grantOf(expansion.bodies().get(2));
        assertEquals(new JsonPrimitive(5), grant.get("Add"), "the step's {First} is the member's own number");
        assertTrue(grant.get("Add").getAsJsonPrimitive().isNumber(), "and stays a number through both passes");
        assertEquals("DamageBonus_Fire", grant.get("Stat").getAsString(),
                "a member's own value naming its own token is filled too");
        assertEquals(new JsonPrimitive(2), grantOf(expansion.bodies().get(1)).get("Add"));
        assertEquals("yours_tier_ii", expansion.bodies().get(1).baseId(), "Base takes the second axis's token");
    }

    @Test
    void aLoopOrATokenNothingBindsSkipsTheEntryWithAnError() throws IOException {
        GeneratorCore.Expansion loop = GeneratorCore.expand(generator("""
                { "Base": "b", "IdPattern": "q_{n}",
                  "ForEach": [ { "Values": [ { "n": 1, "a": "x{b}", "b": "{a}" }, { "n": 2, "a": "fine", "b": "{a}" } ] } ],
                  "Child": { "Text": "{b}" } }
                """), "test", "entry", null);
        assertEquals(List.of("q_2"), loop.bodies().stream().map(GeneratedBody::id).toList(),
                "only the combination caught in the loop is skipped");
        assertEquals(List.of("TOKEN_CYCLE"), codes(loop.issues()));
        assertEquals(Severity.ERROR, loop.issues().get(0).severity());
        assertEquals("fine", loop.bodies().get(0).body().get("Text").getAsString(), "the second pass fills {a}");

        GeneratorCore.Expansion unbound = GeneratorCore.expand(generator("""
                { "Base": "b", "IdPattern": "q_{n}",
                  "ForEach": [ { "Values": [ { "n": 1, "a": "{missing}" } ] } ],
                  "Child": { "Text": "{a}" } }
                """), "test", "entry", null);
        assertTrue(unbound.bodies().isEmpty(), "a half-written entry is not shipped");
        assertEquals(List.of("UNRESOLVED_TOKEN"), codes(unbound.issues()));
    }

    @Test
    void aMoveOfAWalkedSetExpandsItsStoreAgainAndNothingElseDoes(@TempDir Path dir) throws IOException {
        packRows();
        AtomicInteger refolds = new AtomicInteger();
        GeneratorRows.onRefold(QuestGeneratorAsset.class, refolds::incrementAndGet);
        GeneratorRowConfig.setDirectory(dir);
        GeneratorRows.settle();
        assertEquals(0, refolds.get(), "no generator of the type has walked a set yet");

        GeneratorCore.expand(generator(STEPS), "test", "entry", null);
        Files.writeString(dir.resolve(GeneratorRowConfig.OWNER_FILE), """
                { "Yours_School_Fire": { "Values": { "First": 6 } } }
                """, StandardCharsets.UTF_8);
        GeneratorRowConfig.reloadOwner();
        assertEquals(1, refolds.get(), "an owner's retune of a walked set expands the store again");
        GeneratorCore.Expansion retuned = GeneratorCore.expand(generator(STEPS), "test", "entry", null);
        assertEquals(new JsonPrimitive(6), grantOf(retuned.bodies().get(2)).get("Add"), "the retuned value lands");
        assertEquals(new JsonPrimitive(3), grantOf(retuned.bodies().get(3)).get("Add"),
                "and the member's other values stand: an owner entry merges per name");

        GeneratorRowConfig.reloadOwner();
        assertEquals(1, refolds.get(), "a read that moves nothing expands nothing");
        Files.writeString(dir.resolve(GeneratorRowConfig.OWNER_FILE), """
                { "Yours_School_Fire": { "Values": { "First": 6 } },
                  "Yours_Other_Row": { "Set": "Yours_Other", "Values": { "Word": "w" } } }
                """, StandardCharsets.UTF_8);
        GeneratorRowConfig.reloadOwner();
        assertEquals(1, refolds.get(), "a set no generator walked expands nothing");
    }

    @Test
    void aRowsAxisWithNoSetOrAnEmptySetWritesNothingAndSaysSo() throws IOException {
        GeneratorCore.Expansion noSet = GeneratorCore.expand(generator("""
                { "Base": "b", "IdPattern": "q", "ForEach": [ { "Source": "ziggfreedcommon:rows" } ], "Child": { } }
                """), "test", "entry", null);
        assertEquals(List.of("ROWS_WITHOUT_SET"), codes(noSet.issues()));
        GeneratorCore.Expansion empty = GeneratorCore.expand(generator("""
                { "Base": "b", "IdPattern": "q", "ForEach": [ { "Source": "ziggfreedcommon:rows",
                  "Filter": { "Set": "Nobody_Ships_This" } } ], "Child": { } }
                """), "test", "entry", null);
        assertEquals(List.of("EMPTY_AXIS"), codes(empty.issues()));
        assertThrows(Exception.class, () -> row("Yours_Bad", "{ \"Set\": \"x\", \"Values\": { \"A\": { \"B\": 1 } } }"),
                "a row's value is one scalar, never a nested object");
    }

    // ==================== fixtures ====================

    private static void packRows() throws IOException {
        Map<String, GeneratorRowAsset> layer = new LinkedHashMap<>();
        layer.put("yours_school_fire", row("Yours_School_Fire", """
                { "Set": "Yours_Schools", "Order": 20,
                  "Values": { "School": "Fire", "Line": "DamageBonus_{School}", "First": 5, "Second": 3, "Icon": "Fire" } }
                """));
        layer.put("yours_school_ice", row("Yours_School_Ice", """
                { "Set": "yours_schools", "Order": 10,
                  "Values": { "School": "Ice", "Line": "DamageBonus_{School}", "First": 4, "Second": 2 } }
                """));
        layer.put("yours_school_ash", row("Yours_School_Ash", """
                { "Set": "Yours_Schools", "Enabled": false,
                  "Values": { "School": "Ash", "Line": "DamageBonus_Ash", "First": 1, "Second": 1 } }
                """));
        GeneratorRowConfig.getInstance().mergePackLayer(layer);
    }

    private static GeneratorRowAsset row(String id, String json) throws IOException {
        return GeneratorRowAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(GeneratorRowAsset.class, id, null)));
    }

    private static QuestGeneratorAsset generator(String json) throws IOException {
        return QuestGeneratorAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(QuestGeneratorAsset.class, "yours_" + SET, null)));
    }

    private static JsonObject grantOf(GeneratedBody body) {
        return body.body().getAsJsonArray("Grants").get(0).getAsJsonObject();
    }

    private static List<String> codes(List<Finding> issues) {
        return issues.stream().map(Finding::code).toList();
    }
}
