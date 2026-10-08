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

    /** A keyed store over the stand-in asset, as every owner-layered framework store is. */
    private static final class ProbeConfig extends AbstractKeyedAssetConfig<Probe> {
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
}
