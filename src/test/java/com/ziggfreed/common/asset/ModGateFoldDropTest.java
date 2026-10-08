package com.ziggfreed.common.asset;

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
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.asset.BoardValidator;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.LootableValidator;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.stats.gearset.GearSetAsset;
import com.ziggfreed.common.stats.gearset.GearSetConfig;
import com.ziggfreed.common.stats.gearset.GearSetOwnerLayers;
import com.ziggfreed.common.stats.gearset.GearSetValidator;
import com.ziggfreed.common.validation.Finding;

/**
 * Review Focus 1, the server without the MMO: a contract, a loot table that contributes to another and
 * a gear set, each gated on the MMO, folded through the very folds the registrar runs, never reach
 * their store, a contribution, the item index or any validator finding, and each store logs one counted
 * line naming the mod and no file; with the MMO the same files load and contribute and nothing is
 * logged. Driven through the engine's own map with its pack door opened, as the registrar's load event
 * hands it over.
 */
class ModGateFoldDropTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String PACK = "Ziggfreed:SeasonsOfOrbis";
    private static final String GATE = "\"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
            + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] }";
    private static final String ROLLS = "\"Rolls\": [ { \"Grants\": { \"Items\":"
            + " [ { \"Item\": \"Fixture_Gem\", \"Count\": 1 } ] } } ]";

    /** Every mod-gate line the folds logged, in order. */
    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void captureTheDropLines() {
        ModGates.reportIntoForTests(lines::add);
    }

    @AfterEach
    void restore() {
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
        BoardAssetStore.getInstance().merge(Map.of());
        LootableConfig.getInstance().mergePackLayer(Map.of());
        GearSetConfig.getInstance().mergePackLayer(Map.of());
    }

    @AfterEach
    void restoreOwnerLayer() {
        GearSetConfig.getInstance().mergeOwnerLayer(Map.of());
        GearSetOwnerLayers.setDirectory(GearSetOwnerLayers.DEFAULT_DIRECTORY);
    }

    private static void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : null);
    }

    /** The engine map with its pack-loading door opened for a test. */
    private static final class PackMap<T extends JsonAsset<String>> extends DefaultAssetMap<String, T> {

        private final AssetBuilderCodec<String, T> codec;
        private final Class<T> type;

        PackMap(AssetBuilderCodec<String, T> codec, Class<T> type) {
            this.codec = codec;
            this.type = type;
        }

        PackMap<T> load(String id, String json) throws IOException {
            T asset = codec.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                    new AssetExtraInfo<>(new AssetExtraInfo.Data(type, id, null)));
            putAll(PACK, codec, Map.of(id, asset), Map.of(id, Path.of("SeasonsOfOrbis", id + ".json")),
                    Map.of(id, Set.of()));
            return this;
        }
    }

    /** Fold the three stores exactly as their load handlers do, under the registrar's store names. */
    private static void foldThePack() throws IOException {
        PackMap<BountyAsset> contracts = new PackMap<>(BountyAsset.CODEC, BountyAsset.class)
                .load("Mmo_Skill_Job", "{ " + GATE + ", \"Boards\": [ { \"Board\": \"Harvest_Feast_Board\","
                        + " \"Difficulty\": \"Skill\" } ] }")
                .load("Harvest_Feast_Job", "{ \"Boards\": [ { \"Board\": \"Harvest_Feast_Board\","
                        + " \"Difficulty\": \"Normal\" } ] }");
        BoardAssetStore.getInstance().merge(
                AssetMergeAdapter.gate("Bounties", contracts, b -> GateSpec.missingMod(b.getRequires())).layer());

        PackMap<LootableAsset> tables = new PackMap<>(LootableAsset.CODEC, LootableAsset.class)
                .load("Harvest_Feast_Table", "{ " + ROLLS + " }")
                .load("Mmo_Xp_Into_Harvest", "{ " + GATE + ", \"ContributesTo\": \"Harvest_Feast_Table\", " + ROLLS + " }");
        LootableConfig.getInstance().mergePackLayer(
                AssetMergeAdapter.gate("Lootables", tables, t -> PresenceRequiresCodec.missingMod(t.getRequires())));

        PackMap<GearSetAsset> sets = new PackMap<>(GearSetAsset.CODEC, GearSetAsset.class)
                .load("Mmo_Harvest_Set", "{ " + GATE + ", \"Members\": [ \"Harvest_Feast_Hat\" ],"
                        + " \"Bonuses\": [ { \"Pieces\": 1 } ] }");
        GearSetConfig.getInstance().mergePackLayer(
                AssetMergeAdapter.gate("GearSets", sets, s -> PresenceRequiresCodec.missingMod(s.getRequires())));
    }

    /** The exact line a store logs for the one file it dropped (the season boot pair parses it). */
    private static String packLine(String store) {
        return "[zc] mod gate: " + store + " dropped 1 pack file(s) gated on a missing mod (" + MMO + ")";
    }

    private static List<Finding> everyFinding() {
        List<Finding> findings = new ArrayList<>(BoardValidator.validate(BoardConfig.getInstance().all(),
                BoardAssetStore.getInstance().assets(), null, null, null, null, null));
        findings.addAll(LootableValidator.auditAll(null));
        findings.addAll(GearSetValidator.audit(GearSetConfig.getInstance().all().values(),
                id -> true, id -> true, id -> true, id -> Double.NaN));
        return findings;
    }

    private static boolean namesAGatedFile(Finding finding) {
        return (finding.sourceId() + " " + finding.message()).toLowerCase(Locale.ROOT).contains("mmo_");
    }

    @Test
    void withoutTheMmoAGatedFileReachesNoStoreNoContributionNoIndexAndNoFinding() throws IOException {
        mmoInstalled(false);

        foldThePack();

        assertNull(BoardAssetStore.getInstance().assets().get("mmo_skill_job"), "never folded, so never drawn");
        assertNotNull(BoardAssetStore.getInstance().assets().get("harvest_feast_job"), "its ungated neighbour is");
        assertNull(LootableConfig.getInstance().resolve("mmo_xp_into_harvest"));
        assertEquals(List.of(), LootableConfig.getInstance().contributorsOf("harvest_feast_table"),
                "the table it would enrich pays exactly what its own file says");
        assertNull(GearSetConfig.getInstance().resolve("mmo_harvest_set"));
        assertTrue(GearSetConfig.getInstance().index().setsFor("Harvest_Feast_Hat").isEmpty(),
                "the hat lights no set notice");
        for (Finding finding : everyFinding()) {
            assertFalse(namesAGatedFile(finding), "no audit line names the MMO layer: " + finding);
        }
        assertEquals(List.of(packLine("Bounties"), packLine("Lootables"), packLine("GearSets")), lines,
                "one counted line per store, naming the missing mod and no file");
    }

    @Test
    void withTheMmoTheSameFilesLoadContributeAndAreAudited() throws IOException {
        mmoInstalled(true);

        foldThePack();

        assertNotNull(BoardAssetStore.getInstance().assets().get("mmo_skill_job"));
        assertEquals(List.of("mmo_xp_into_harvest"), LootableConfig.getInstance().contributorsOf("harvest_feast_table"));
        assertEquals(1, GearSetConfig.getInstance().index().setsFor("Harvest_Feast_Hat").size());
        assertTrue(everyFinding().stream().anyMatch(ModGateFoldDropTest::namesAGatedFile),
                "the validators do read the files once they load (the contract names no loaded board)");
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }

    /**
     * Owner files follow the mod gate (M295): an owner retune of a pack file the gate refused goes with
     * that file, even though the retune writes no gate of its own, so where the MMO is missing nothing of
     * the set loads, the hat lights nothing, and the store logs one counted owner line naming the mod and
     * no entry. With the MMO the same retune merges over the pack's file and keeps its members and its gate.
     */
    @Test
    void anOwnerRetuneOfADroppedSetGoesWithItAndWithTheMmoMergesOverIt(@TempDir Path ownerDir)
            throws IOException {
        GearSetOwnerLayers.setDirectory(ownerDir);
        Files.writeString(ownerDir.resolve(GearSetOwnerLayers.FILE),
                "{ \"Mmo_Harvest_Set\": { \"Enabled\": true } }", StandardCharsets.UTF_8);

        mmoInstalled(false);
        foldThePack();
        GearSetOwnerLayers.reload();

        assertNull(GearSetConfig.getInstance().resolve("mmo_harvest_set"),
                "the retune follows the pack file it overrides out of the store");
        assertTrue(GearSetConfig.getInstance().index().setsFor("Harvest_Feast_Hat").isEmpty(),
                "the hat lights no set notice");
        assertTrue(lines.contains("[zc] mod gate: GearSets dropped 1 owner override(s) gated on a missing mod ("
                + MMO + ")"), "one counted owner line: " + lines);
        for (String line : lines) {
            assertFalse(line.toLowerCase(Locale.ROOT).contains("mmo_"), "a drop line names no file: " + line);
        }

        lines.clear();
        mmoInstalled(true);
        foldThePack();
        GearSetOwnerLayers.reload();

        GearSetAsset retune = GearSetConfig.getInstance().resolve("mmo_harvest_set");
        assertNotNull(retune);
        assertNotNull(retune.getRequires(), "with the MMO the retune merges over the pack's file and keeps its gate");
        assertEquals(1, GearSetConfig.getInstance().index().setsFor("Harvest_Feast_Hat").size());
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }
}
