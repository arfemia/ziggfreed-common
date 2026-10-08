package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetStoreContributionTest.amountOf;
import static com.ziggfreed.common.quest.asset.QuestAssetStoreContributionTest.quest;
import static com.ziggfreed.common.quest.asset.QuestGatedBaseTest.foldAsTheLoadHandlerDoes;
import static com.ziggfreed.common.quest.asset.QuestGeneratorTest.generator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.quest.asset.QuestGatedBaseTest.PackMap;
import com.ziggfreed.common.validation.Finding;

/**
 * The owner quest folder follows the mod gate: an owner quest whose own {@code Requires} gates on a
 * missing mod is dropped, an owner retune of a pack quest the gate refused goes with it, and an owner
 * quest whose {@code Parent} is one of those follows its base out, as a generated family does. None of
 * them leaves a finding or a line naming it; the store logs one counted owner line per missing mod.
 */
class QuestOwnerModGateTest {

    private static final QuestAssetStore STORE = QuestAssetStore.getInstance();
    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String GATE = "\"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
            + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] }";
    private static final String STEP = "\"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\","
            + " \"Target\": \"Ore\", \"Amount\": 5 } }";
    private static final String RETUNE = "{ \"Objectives\": { \"collect\": { \"Amount\": 25 } } }";

    @TempDir
    Path dir;

    private final List<String> lines = new ArrayList<>();
    private PackMap packs;

    @BeforeEach
    void twoGatedPackQuestsAnUngatedOneAndAnOwnerFolderOverThem() throws IOException {
        QuestOwnerLayers.setDirectory(dir);
        Path folder = Files.createDirectories(dir.resolve(QuestOwnerLayers.FOLDER));
        ModGates.reportIntoForTests(lines::add);
        packs = new PackMap()
                .load("Mmo_Gather", "Server/ZiggfreedCommon/Quests/Mmo/Mmo_Gather.json", "{ " + GATE + ", " + STEP + " }")
                .load("Mmo_Hunt", "Server/ZiggfreedCommon/Quests/Mmo/Mmo_Hunt.json", "{ " + GATE + ", " + STEP + " }")
                .load("Harvest_Gather", "Server/ZiggfreedCommon/Quests/Harvest/Harvest_Gather.json", "{ " + STEP + " }");
        write(folder, "Mmo_Gather.json", RETUNE);
        write(folder, "Owner_Mmo_Errand.json", "{ " + GATE + ", " + STEP + " }");
        write(folder, "Owner_Mmo_Sequel.json", "{ \"Parent\": \"Mmo_Gather\", \"Text\": { \"TitleKey\": \"quest.sequel\" } }");
        write(folder, "Owner_Hunt_Sequel.json", "{ \"Parent\": \"Mmo_Hunt\", \"Text\": { \"TitleKey\": \"quest.hunt\" } }");
        write(folder, "Harvest_Gather.json", RETUNE);
        write(folder, "Owner_Errand.json", "{ " + STEP + " }");
    }

    @AfterEach
    void clearEverything() {
        QuestOwnerLayers.setDirectory(QuestOwnerLayers.DEFAULT_DIRECTORY);
        STORE.mergeQuests(Map.of());
        STORE.mergeGenerators(Map.of());
        STORE.mergeContributed(Map.of());
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
    }

    private static void write(Path folder, String fileName, String json) throws IOException {
        Files.writeString(folder.resolve(fileName), json, StandardCharsets.UTF_8);
    }

    private static void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : 1.0);
    }

    @Test
    void withoutTheMmoTheOwnerQuestsOfItsContentAreDroppedWithOneLineAndNoFinding() {
        mmoInstalled(false);
        foldAsTheLoadHandlerDoes(packs);

        QuestAssetStore.Resolution resolution = STORE.resolve(null);
        QuestPool pool = resolution.pool();

        assertNull(pool.definition("mmo_gather"), "a retune of a refused pack quest goes with it");
        assertNull(pool.definition("owner_mmo_errand"), "an owner quest gated on the missing mod is dropped");
        assertNull(pool.definition("owner_mmo_sequel"), "a child of a dropped owner retune follows it");
        assertNull(pool.definition("owner_hunt_sequel"), "a child of a refused pack quest follows it");
        assertEquals(25, amountOf(pool, "harvest_gather"), "a retune of a loaded quest still merges");
        assertNotNull(pool.definition("owner_errand"), "an ungated owner quest stays");
        for (Finding finding : resolution.issues()) {
            assertFalse((finding.sourceId() + " " + finding.message()).toLowerCase(Locale.ROOT).contains("mmo_"),
                    "no finding names the missing mod's content: " + finding);
        }
        assertEquals(List.of(
                "[zc] mod gate: Quests dropped 2 pack file(s) gated on a missing mod (" + MMO + ")",
                "[zc] mod gate: Quests dropped 4 owner override(s) gated on a missing mod (" + MMO + ")"), lines);
    }

    @Test
    void withTheMmoEveryOwnerQuestLoadsAndTheRetuneMergesOverItsPackQuest() {
        mmoInstalled(true);
        foldAsTheLoadHandlerDoes(packs);

        QuestPool pool = STORE.resolve(null).pool();

        assertEquals(25, amountOf(pool, "mmo_gather"));
        assertNotNull(pool.definition("owner_mmo_errand"));
        assertNotNull(pool.definition("owner_mmo_sequel"));
        assertNotNull(pool.definition("owner_hunt_sequel"));
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }

    // ==================== the owner's own gate on a quest the pack ships ungated (M295 fix round) ====================

    /**
     * The owner gates two quests that ship with no gate: the pack's {@code Harvest_Gather} and a contributed
     * {@code Harvest_Hunt}. An owner sequel names the first as its Parent, a generator family stands on it,
     * and a second generator writes a quest under the second's id.
     */
    private void anOwnerGateOverQuestsThatShipUngated() throws Exception {
        Path folder = dir.resolve(QuestOwnerLayers.FOLDER);
        write(folder, "Harvest_Gather.json", "{ " + GATE + " }");
        write(folder, "Harvest_Hunt.json", "{ " + GATE + " }");
        write(folder, "Owner_Harvest_Sequel.json",
                "{ \"Parent\": \"Harvest_Gather\", \"Text\": { \"TitleKey\": \"quest.harvest_sequel\" } }");
        STORE.mergeContributed(Map.of("harvest_hunt", quest("harvest_hunt", 4)));
        STORE.mergeGenerators(Map.of(
                "harvest_ladder", generator("{ \"Base\": \"harvest_gather\", \"IdPattern\": \"harvest_gather_{material}\","
                        + " \"ForEach\": [ { \"Token\": \"material\", \"Values\": [\"copper\"] } ],"
                        + " \"Child\": { \"Objectives\": { \"collect\": { \"Target\": \"{material}_Ore\" } } } }",
                        "harvest_ladder"),
                "errand_ladder", generator("{ \"Base\": \"owner_errand\", \"IdPattern\": \"harvest_{kind}\","
                        + " \"ForEach\": [ { \"Token\": \"kind\", \"Values\": [\"hunt\"] } ],"
                        + " \"Child\": { \"Objectives\": { \"collect\": { \"Target\": \"Bone\" } } } }",
                        "errand_ladder")));
    }

    /**
     * The maintainer's ruling: an owner quest gated on a missing mod takes that whole id out, the pack's
     * version and the contributed one included, exactly as a gated pack file would. The id then counts as
     * refused: a family over it and a quest whose Parent it is follow it out, a generated quest under its id
     * is out too, and no finding names it. The store's owner line counts each.
     */
    @Test
    void withoutTheMmoAnOwnerGateOnAnUngatedQuestTakesTheWholeIdOutAndWhatStandsOnItFollows() throws Exception {
        anOwnerGateOverQuestsThatShipUngated();
        mmoInstalled(false);
        foldAsTheLoadHandlerDoes(packs);

        QuestAssetStore.Resolution resolution = STORE.resolve(null);
        QuestPool pool = resolution.pool();

        assertNull(pool.definition("harvest_gather"), "the pack's ungated quest does not stand in for the owner's");
        assertNull(pool.definition("harvest_hunt"), "nor does the contributed one, or one a generator writes");
        assertNull(pool.definition("owner_harvest_sequel"), "a quest whose Parent it is follows it out");
        assertNull(pool.definition("harvest_gather_copper"), "and so does the family over it");
        for (Finding finding : resolution.issues()) {
            assertFalse((finding.sourceId() + " " + finding.message()).toLowerCase(Locale.ROOT).contains("harvest_"),
                    "no finding names a quest the owner's gate took out: " + finding);
        }
        assertEquals(List.of(
                "[zc] mod gate: Quests dropped 2 pack file(s) gated on a missing mod (" + MMO + ")",
                "[zc] mod gate: Quests dropped 7 owner override(s) gated on a missing mod (" + MMO + ")"), lines);
        assertFalse(STORE.composedAssets().containsKey("harvest_gather"), "the composed view leaves it out too");
    }

    @Test
    void withTheMmoTheOwnersGateMergesOverTheUngatedQuestAndKeepsIt() throws Exception {
        anOwnerGateOverQuestsThatShipUngated();
        mmoInstalled(true);
        foldAsTheLoadHandlerDoes(packs);

        QuestPool pool = STORE.resolve(null).pool();

        assertEquals(5, amountOf(pool, "harvest_gather"), "it keeps the step the pack wrote");
        assertNotNull(STORE.composedAssets().get("harvest_gather").getRequires(), "and the gate the owner added");
        assertEquals(4, amountOf(pool, "harvest_hunt"), "the contributed quest's step, under the owner's gate");
        assertNotNull(pool.definition("owner_harvest_sequel"));
        assertNotNull(pool.definition("harvest_gather_copper"));
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }
}
