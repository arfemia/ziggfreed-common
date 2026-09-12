package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static com.ziggfreed.common.quest.asset.QuestAssetStoreContributionTest.amountOf;
import static com.ziggfreed.common.quest.asset.QuestAssetStoreContributionTest.codes;
import static com.ziggfreed.common.quest.asset.QuestAssetStoreContributionTest.quest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.validation.Finding;

/**
 * The server owner's quest folder, {@code mods/ziggfreedcommon/quests/<Id>.json}, as the store
 * reads it: one quest per file in the pack shape, the file name the id, laid over every other layer
 * at every fold.
 *
 * <p>The two rules that matter most are the ones a hand-rolled reader gets wrong. A same-id file
 * has to MERGE over the quest below it, leaf by leaf, or an owner retuning one number silently
 * loses every step the pack wrote; and a malformed file has to cost that one quest rather than the
 * boot, because an admin editing JSON by hand at 2am is the normal case rather than the exceptional
 * one. Everything else here is the reporting: a file that will not read, a Parent nothing has, a
 * cycle, and the entries the folder ignores.
 */
class QuestOwnerLayersTest {

    private static final QuestAssetStore STORE = QuestAssetStore.getInstance();

    @TempDir
    Path dir;

    private Path folder;

    @BeforeEach
    void pointAtTheTempDirectory() throws IOException {
        QuestOwnerLayers.setDirectory(dir);
        folder = Files.createDirectories(dir.resolve(QuestOwnerLayers.FOLDER));
        STORE.mergeQuests(Map.of());
        STORE.mergeGenerators(Map.of());
        STORE.mergeContributed(Map.of());
    }

    @AfterEach
    void clearEverything() {
        QuestOwnerLayers.setDirectory(QuestOwnerLayers.DEFAULT_DIRECTORY);
        STORE.mergeQuests(Map.of());
        STORE.mergeGenerators(Map.of());
        STORE.mergeContributed(Map.of());
    }

    private Path write(String fileName, String json) throws IOException {
        return Files.writeString(folder.resolve(fileName), json, StandardCharsets.UTF_8);
    }

    /** A quest with a title key and one step, so a test can see which leaves survive a merge. */
    private static QuestAsset titled(String id, String titleKey, long amount) throws Exception {
        return decodeRoot("{ \"Text\": { \"TitleKey\": \"" + titleKey + "\" }, \"Objectives\": { \"collect\": "
                + "{ \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\", \"Amount\": " + amount + " } } }", id);
    }

    private static String titleKeyOf(QuestPool pool, String id) {
        QuestDefinition definition = pool.definition(id);
        assertNotNull(definition, id + " should be in the pool");
        return definition.titleKey();
    }

    // ==================== landing ====================

    @Test
    void noFolderAtAllIsNothing() throws Exception {
        Files.delete(folder);
        STORE.mergeQuests(Map.of("shipped", quest("shipped", 10)));

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertEquals(10L, amountOf(resolution.pool(), "shipped"));
        assertTrue(resolution.issues().isEmpty(), "a missing folder is the common case and says nothing");
    }

    @Test
    void anOwnerFileForANewIdStandsOnItsOwnUnderTheLowerCasedFileName() throws Exception {
        write("Town_Errand.json", "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\","
                + " \"Target\": \"Ore\", \"Amount\": 3 } } }");

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertEquals(3L, amountOf(resolution.pool(), "town_errand"));
        assertTrue(resolution.issues().isEmpty(), "nothing to report: " + resolution.issues());
        assertTrue(STORE.composedAssets().containsKey("town_errand"), "the composed view carries it too");
    }

    @Test
    void aSameIdOwnerFileRetunesOneLeafAndKeepsTheRest() throws Exception {
        STORE.mergeQuests(Map.of("gather_copper", titled("gather_copper", "quest.gather_copper.title", 10)));
        write("Gather_Copper.json", "{ \"Objectives\": { \"collect\": { \"Amount\": 25 } } }");

        QuestPool pool = STORE.resolve(null).pool();

        assertEquals(25L, amountOf(pool, "gather_copper"), "the one leaf the owner wrote");
        assertEquals("Ore", pool.definition("gather_copper").quest().objective("collect").target(),
                "the step's other leaves come from the shipped quest");
        assertEquals("quest.gather_copper.title", titleKeyOf(pool, "gather_copper"),
                "and so does every group the owner did not restate");
    }

    // ==================== precedence ====================

    @Test
    void theOwnerFileSitsAboveTheContributedLayerAndInheritsFromIt() throws Exception {
        STORE.mergeQuests(Map.of("q", titled("q", "quest.q.shipped", 10)));
        STORE.mergeContributed(Map.of("q", titled("q", "quest.q.converted", 20)));
        write("Q.json", "{ \"Objectives\": { \"collect\": { \"Amount\": 30 } } }");

        QuestPool pool = STORE.resolve(null).pool();

        assertEquals(30L, amountOf(pool, "q"), "the owner's word is the last word");
        assertEquals("quest.q.converted", titleKeyOf(pool, "q"),
                "what the owner did not write comes from the layer directly below, the contributed one");
    }

    @Test
    void theContributedLayerStillStandsWhereNoOwnerFileNamesTheId() throws Exception {
        STORE.mergeQuests(Map.of("q", quest("q", 10)));
        STORE.mergeContributed(Map.of("q", quest("q", 20)));
        write("Other.json", "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } } }");

        assertEquals(20L, amountOf(STORE.resolve(null).pool(), "q"));
    }

    @Test
    void anOwnerFileIsInheritedFromByAGeneratorAndBeatsAGeneratedId() throws Exception {
        write("Gather_Base.json", "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\","
                + " \"Target\": \"Ore\", \"Amount\": 7 } } }");
        write("Gather_Iron.json", "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\","
                + " \"Target\": \"Ore\", \"Amount\": 99 } } }");
        STORE.mergeGenerators(Map.of("ladder", QuestGeneratorTest.generator("""
                { "Base": "gather_base", "IdPattern": "gather_{material}",
                  "ForEach": [ { "Token": "material", "Values": ["copper", "iron"] } ],
                  "Child": { "Objectives": { "collect": { "Target": "{material}_Ore" } } } }
                """, "ladder")));

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertEquals(7L, amountOf(resolution.pool(), "gather_copper"), "an owner file may be a generator's base");
        assertEquals(99L, amountOf(resolution.pool(), "gather_iron"), "and an owner file beats a generated id");
        assertTrue(codes(resolution.issues()).contains("ID_COLLISION"));
    }

    // ==================== Parent ====================

    @Test
    void anExplicitParentInheritsFromThatQuestInsteadOfTheSameId() throws Exception {
        STORE.mergeQuests(Map.of(
                "gather_copper", titled("gather_copper", "quest.gather_copper.title", 10),
                "town_errand", titled("town_errand", "quest.town_errand.shipped", 1)));
        write("Town_Errand.json", "{ \"Parent\": \"gather_copper\", \"Text\": { \"TitleKey\": \"quest.town_errand.mine\" } }");

        QuestPool pool = STORE.resolve(null).pool();

        assertEquals(10L, amountOf(pool, "town_errand"), "the step comes from the named Parent");
        assertEquals("quest.town_errand.mine", titleKeyOf(pool, "town_errand"));
        assertEquals(10L, amountOf(pool, "gather_copper"), "and the Parent itself is untouched");
    }

    @Test
    void anOwnerFileMayInheritFromAnotherOwnerFileInAnyNameOrder() throws Exception {
        // 'Aaa' sorts before 'Zzz_Base', so the child is met before its base.
        write("Zzz_Base.json", "{ \"Abstract\": true, \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\","
                + " \"Target\": \"Ore\", \"Amount\": 4 } } }");
        write("Aaa.json", "{ \"Parent\": \"zzz_base\", \"Objectives\": { \"collect\": { \"Target\": \"Copper_Ore\" } } }");

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertEquals(4L, amountOf(resolution.pool(), "aaa"));
        assertEquals("Copper_Ore", resolution.pool().definition("aaa").quest().objective("collect").target());
        assertNull(resolution.pool().definition("zzz_base"), "a skeleton exists only to be inherited from");
        assertTrue(resolution.issues().isEmpty(), "nothing to report: " + resolution.issues());
    }

    @Test
    void aParentNothingHasIsReportedAndCostsThatOneQuest() throws Exception {
        write("Orphan.json", "{ \"Parent\": \"nobody\", \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\","
                + " \"Target\": \"Ore\" } } }");
        write("Fine.json", "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } } }");

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertNull(resolution.pool().definition("orphan"));
        assertNotNull(resolution.pool().definition("fine"), "the rest of the folder is carried");
        Finding finding = only(resolution.issues(), "UNKNOWN_PARENT");
        assertEquals("orphan", finding.sourceId());
        assertTrue(finding.message().contains("Orphan.json") && finding.message().contains("'nobody'"),
                "the file and the missing Parent are both named: " + finding.message());
    }

    @Test
    void aParentCycleIsReportedRatherThanLooped() throws Exception {
        write("A.json", "{ \"Parent\": \"b\" }");
        write("B.json", "{ \"Parent\": \"a\" }");

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertNull(resolution.pool().definition("a"));
        assertNull(resolution.pool().definition("b"));
        assertTrue(codes(resolution.issues()).contains("PARENT_CYCLE"), "reported: " + resolution.issues());
    }

    // ==================== what a malformed file costs ====================

    @Test
    void aMalformedFileCostsOnlyItselfAndNamesTheFile() throws Exception {
        STORE.mergeQuests(Map.of("shipped", quest("shipped", 10)));
        Path broken = write("Broken.json", "{ this is not json");
        write("Fine.json", "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } } }");

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertNull(resolution.pool().definition("broken"));
        assertNotNull(resolution.pool().definition("fine"), "the rest of the folder is carried");
        assertEquals(10L, amountOf(resolution.pool(), "shipped"), "and every other layer folds as before");
        Finding finding = only(resolution.issues(), "OWNER_FILE_UNREADABLE");
        assertEquals("broken", finding.sourceId());
        assertTrue(finding.message().contains(broken.getFileName().toString()), "named: " + finding.message());
    }

    @Test
    void aFileThatIsNotAQuestBodyIsReportedTheSameWay() throws Exception {
        write("List.json", "[ 1, 2, 3 ]");

        Finding finding = only(STORE.resolve(null).issues(), "OWNER_FILE_UNREADABLE");
        assertEquals("list", finding.sourceId());
    }

    @Test
    void aBodyTheCodecRefusesIsReportedAsADecodeFailureNamingTheFile() throws Exception {
        write("Odd.json", "{ \"Objectives\": \"not a map of steps\" }");

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertNull(resolution.pool().definition("odd"));
        Finding finding = only(resolution.issues(), "DECODE_FAILED");
        assertEquals("odd", finding.sourceId());
        assertTrue(finding.message().contains("Odd.json"), "named: " + finding.message());
    }

    @Test
    void aSameIdFileThatWillNotReadLeavesTheShippedQuestStanding() throws Exception {
        STORE.mergeQuests(Map.of("shipped", quest("shipped", 10)));
        write("Shipped.json", "{ broken");

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertEquals(10L, amountOf(resolution.pool(), "shipped"), "the one in circulation stands");
        assertTrue(codes(resolution.issues()).contains("OWNER_FILE_UNREADABLE"));
    }

    // ==================== what the folder ignores ====================

    @Test
    void documentationNonJsonEntriesAndSubFoldersAreIgnored() throws Exception {
        write("$README.json", "{ broken on purpose, it is documentation");
        write("notes.txt", "not a quest");
        write("Draft.json.bak", "{ not read }");
        Files.createDirectories(folder.resolve("Nested"));
        Files.writeString(folder.resolve("Nested").resolve("Deep.json"),
                "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } } }");
        write("Real.json", "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } } }");

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertNotNull(resolution.pool().definition("real"));
        assertNull(resolution.pool().definition("deep"), "the folder is flat");
        assertNull(resolution.pool().definition("$readme"));
        assertTrue(resolution.issues().isEmpty(), "an ignored entry is not a finding: " + resolution.issues());
    }

    // ==================== where it was read from ====================

    @Test
    void anOwnerQuestRemembersItsFileSoAFindingCanNameIt() throws Exception {
        Path file = write("Mine.json", "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } } }");

        QuestAsset asset = STORE.composedAssets().get("mine");

        assertNotNull(asset);
        assertEquals(file.toString(), asset.getSourcePath());
        assertFalse(asset.isAbstract());
    }

    @Test
    void theFolderIsReReadByEveryFold() throws Exception {
        assertNull(STORE.resolve(null).pool().definition("late"));

        write("Late.json", "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } } }");
        assertNotNull(STORE.resolve(null).pool().definition("late"), "a file dropped in is seen by the next fold");

        Files.delete(folder.resolve("Late.json"));
        assertNull(STORE.resolve(null).pool().definition("late"), "and a file removed is gone from the next");
    }

    private static Finding only(List<Finding> issues, String code) {
        List<Finding> matching = issues.stream().filter(f -> code.equals(f.code())).toList();
        assertEquals(1, matching.size(), "exactly one " + code + " expected in " + issues);
        return matching.get(0);
    }
}
