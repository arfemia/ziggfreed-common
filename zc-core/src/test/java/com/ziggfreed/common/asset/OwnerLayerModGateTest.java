package com.ziggfreed.common.asset;

import static com.ziggfreed.common.asset.AssetMergeAdapterKeepTest.GATED;
import static com.ziggfreed.common.asset.AssetMergeAdapterKeepTest.MISSING_MOD;
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

import com.ziggfreed.common.asset.AssetMergeAdapterKeepTest.PackMap;
import com.ziggfreed.common.asset.AssetMergeAdapterKeepTest.Probe;
import com.ziggfreed.common.factor.ModGates;

/**
 * Owner files follow the mod gate, through the shared reader every keyed store's owner file goes
 * through: an owner entry whose own {@code Requires} gates on a missing mod is dropped, and an owner
 * override of a pack file the gate refused goes with that file even when it writes no gate of its own.
 * Neither leaves a line naming the entry; the store logs one counted line per missing mod.
 */
class OwnerLayerModGateTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";

    /** A keyed store over the stand-in asset, declaring its label as every gated framework store does. */
    private static final class ProbeConfig extends AbstractKeyedAssetConfig<Probe> {

        ProbeConfig() {
            super("Probes");
        }
    }

    @TempDir
    Path dir;

    private final ProbeConfig config = new ProbeConfig();
    private final List<String> lines = new ArrayList<>();
    private PackMap packs;
    private Path ownerFile;

    @BeforeEach
    void aGatedAndAnUngatedPackFileAndAnOwnerFileOverBoth() throws IOException {
        ModGates.reportIntoForTests(lines::add);
        packs = new PackMap()
                .load("Mmo_Skill_Job", GATED)
                .load("Harvest_Feast_Job", "{}");
        ownerFile = Files.writeString(dir.resolve("probes.json"), "{"
                + " \"Mmo_Skill_Job\": { },"
                + " \"Owner_Mmo_Extra\": " + GATED + ","
                + " \"Harvest_Feast_Job\": { },"
                + " \"Owner_Own_Job\": { } }", StandardCharsets.UTF_8);
    }

    @AfterEach
    void restore() {
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
    }

    private void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : 1.0);
    }

    /** Fold the packs and read the owner file exactly as a gated store's load handler does. */
    private void load() {
        config.mergePackLayer(AssetMergeAdapter.gate("Probes", packs, MISSING_MOD));
        OwnerLayerReader.apply("probe", ownerFile, Probe.class, Probe.CODEC, config, "probe", MISSING_MOD);
    }

    @Test
    void withoutTheModItsOwnerEntriesAndOverridesAreDroppedWithOneLinePerKind() {
        mmoInstalled(false);

        load();

        assertNull(config.resolve("mmo_skill_job"),
                "an override of a refused pack file goes with it, though it writes no gate of its own");
        assertNull(config.resolve("owner_mmo_extra"), "an owner entry gated on the missing mod is dropped");
        assertNotNull(config.resolve("harvest_feast_job"), "an override of a loaded file stays");
        assertNotNull(config.resolve("owner_own_job"), "an ungated entry of the owner's own stays");
        assertEquals(List.of(
                "[zc] mod gate: Probes dropped 1 pack file(s) gated on a missing mod (" + MMO + ")",
                "[zc] mod gate: Probes dropped 2 owner override(s) gated on a missing mod (" + MMO + ")"), lines);
        for (String line : lines) {
            assertFalse(line.toLowerCase(Locale.ROOT).contains("mmo_"), "a drop line names no entry: " + line);
        }
    }

    @Test
    void withTheModEveryEntryLoadsAndTheOverrideMergesOverItsPackFile() {
        mmoInstalled(true);

        load();

        Probe override = config.resolve("mmo_skill_job");
        assertNotNull(override);
        assertNotNull(override.requires, "it merged over the pack's file and kept its gate");
        assertNotNull(config.resolve("owner_mmo_extra"));
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }

    @Test
    void aPlainPackFoldRefusesNothingSoNoOwnerOverrideFollowsAStaleRefusal() {
        mmoInstalled(false);
        load();

        config.mergePackLayer(AssetMergeAdapter.layer(packs));
        OwnerLayerReader.apply("probe", ownerFile, Probe.class, Probe.CODEC, config, "probe", probe -> null);

        assertNotNull(config.resolve("mmo_skill_job"), "the last fold refused nothing, so nothing is followed");
        assertTrue(config.modGateRefused().isEmpty());
    }

    // ==================== the owner's own gate on a file the pack ships ungated (M295 fix round) ====================

    private void ownerWrites(String json) throws IOException {
        Files.writeString(ownerFile, json, StandardCharsets.UTF_8);
    }

    /**
     * The maintainer's ruling: an owner entry gated on a missing mod takes that whole id out, exactly as a
     * gated pack file would, so the owner's restriction is never undone by the pack's own ungated version.
     * The jar default under that id goes too, the id counts as refused, and the owner line counts it.
     */
    @Test
    void anOwnerGateOnAPackFileThatShipsWithoutOneTakesTheWholeIdOut() throws IOException {
        mmoInstalled(false);
        config.loadDefaults(Map.of("Harvest_Feast_Job", new Probe()));
        ownerWrites("{ \"Harvest_Feast_Job\": " + GATED + " }");

        load();

        assertNull(config.resolve("harvest_feast_job"), "neither the pack's version nor the jar default stands");
        assertFalse(config.has("harvest_feast_job"));
        assertFalse(config.all().containsKey("harvest_feast_job"), "the folded view leaves it out");
        assertFalse(config.ids().contains("harvest_feast_job"));
        assertEquals(MMO, config.modGateRefused().get("harvest_feast_job"),
                "it counts as refused downstream, as a refused pack file does");
        assertEquals(List.of(
                "[zc] mod gate: Probes dropped 1 pack file(s) gated on a missing mod (" + MMO + ")",
                "[zc] mod gate: Probes dropped 1 owner override(s) gated on a missing mod (" + MMO + ")"), lines);
    }

    @Test
    void withTheModTheOwnersGateMergesOverTheUngatedPackFileAndKeepsItsGate() throws IOException {
        mmoInstalled(true);
        ownerWrites("{ \"Harvest_Feast_Job\": " + GATED + " }");

        load();

        Probe merged = config.resolve("harvest_feast_job");
        assertNotNull(merged, "with the mod the owner's entry is in force");
        assertNotNull(merged.requires, "and keeps the gate it adds");
        assertTrue(config.modGateRefused().isEmpty());
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }

    @Test
    void anOwnerWhoTakesTheGateOffGetsThePackFileBackOnTheNextRead() throws IOException {
        mmoInstalled(false);
        ownerWrites("{ \"Harvest_Feast_Job\": " + GATED + " }");
        load();
        assertNull(config.resolve("harvest_feast_job"));

        ownerWrites("{ }");
        OwnerLayerReader.apply("probe", ownerFile, Probe.class, Probe.CODEC, config, "probe", MISSING_MOD);

        assertNotNull(config.resolve("harvest_feast_job"), "a re-read keeps no take-out the file no longer writes");
        assertFalse(config.modGateRefused().containsKey("harvest_feast_job"));
    }

    /**
     * The owner line names the store's declared contract label even when no gated fold has run yet (a
     * plain fold here), never the config's class name. The retune of the gated pack file inherits its
     * gate, so it is taken out on its own gate like the owner's own gated entry.
     */
    @Test
    void anOwnerLineBeforeAnyGatedFoldNamesTheStoresContractLabel() {
        mmoInstalled(false);

        config.mergePackLayer(AssetMergeAdapter.layer(packs));
        OwnerLayerReader.apply("probe", ownerFile, Probe.class, Probe.CODEC, config, "probe", MISSING_MOD);

        assertEquals("Probes", config.modGateStore());
        assertEquals(List.of(
                "[zc] mod gate: Probes dropped 2 owner override(s) gated on a missing mod (" + MMO + ")"), lines);
        assertNull(config.resolve("mmo_skill_job"), "the pack file the retune inherits its gate from goes too");
    }

    /** A store that rebuilds a derived view inside its own pack merge, as LootableConfig does. */
    private static final class WatchingConfig extends AbstractKeyedAssetConfig<Probe> {

        Map<String, String> refusedInsideTheMerge;

        @Override
        public synchronized void mergePackLayer(Map<String, Probe> layer) {
            super.mergePackLayer(layer);
            refusedInsideTheMerge = modGateRefused();
        }
    }

    /**
     * A gated fold runs the store's own merge for its derived views, and the refusals are in place by the
     * time that merge has rebuilt the layer: no reader, an owner read racing the fold included, ever sees
     * them reset to nothing in between.
     */
    @Test
    void aGatedFoldsRefusalsAreNeverEmptyWhileTheStoresOwnMergeRuns() {
        mmoInstalled(false);
        WatchingConfig watching = new WatchingConfig();

        watching.mergePackLayer(AssetMergeAdapter.gate("Probes", packs, MISSING_MOD));
        assertEquals(Map.of("mmo_skill_job", MMO), watching.refusedInsideTheMerge);

        watching.mergePackLayer(AssetMergeAdapter.gate("Probes", packs, MISSING_MOD));
        assertEquals(Map.of("mmo_skill_job", MMO), watching.refusedInsideTheMerge, "a re-fold too");
    }
}
