package com.ziggfreed.common.npc.placement.asset;

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
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.AssetMergeAdapter;
import com.ziggfreed.common.factor.ModGates;

/**
 * The placement owner file follows the mod gate through its own reader: a placement body whose own
 * {@code Requires} gates on a missing mod is dropped, and an owner body over a pack placement the gate
 * refused goes with it even when it writes no gate of its own. The store logs one counted owner line per
 * missing mod and names no placement.
 */
class NpcPlacementOwnerModGateTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String GATE = "\"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
            + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] }";

    /** The engine map with its pack-loading door opened, as the load event hands it over. */
    private static final class PackMap extends DefaultAssetMap<String, NpcPlacementAsset> {

        PackMap load(String id, String json) throws IOException {
            NpcPlacementAsset asset = NpcPlacementAsset.CODEC.decodeAndInheritJsonAsset(
                    RawJsonReader.fromJsonString(json), null,
                    new AssetExtraInfo<>(new AssetExtraInfo.Data(NpcPlacementAsset.class, id, null)));
            putAll("Ziggfreed:SeasonsOfOrbis", NpcPlacementAsset.CODEC, Map.of(id, asset),
                    Map.of(id, Path.of("SeasonsOfOrbis", id + ".json")), Map.of(id, Set.of()));
            return this;
        }
    }

    @TempDir
    Path dir;

    private final List<String> lines = new ArrayList<>();
    private PackMap packs;

    @BeforeEach
    void aGatedAndAnUngatedPlacementAndAnOwnerFileOverBoth() throws IOException {
        ModGates.reportIntoForTests(lines::add);
        packs = new PackMap()
                .load("Mmo_Trainer", "{ " + GATE + ", \"Identity\": { \"Role\": \"Zc_Trainer\" } }")
                .load("Harvest_Greeter", "{ \"Identity\": { \"Role\": \"Zc_Greeter\" } }");
        Path file = Files.writeString(dir.resolve("npc-placements.json"), "{"
                + " \"Mmo_Trainer\": { \"Identity\": { \"Role\": \"Zc_Other\" } },"
                + " \"Owner_Mmo_Guard\": { " + GATE + ", \"Identity\": { \"Role\": \"Zc_Guard\" } },"
                + " \"Harvest_Greeter\": { \"Identity\": { \"Role\": \"Zc_Other\" } },"
                + " \"Owner_Guard\": { \"Identity\": { \"Role\": \"Zc_Guard\" } } }", StandardCharsets.UTF_8);
        NpcPlacementOverrides.getInstance().setFile(file);
    }

    @AfterEach
    void restore() {
        NpcPlacementOverrides.getInstance().setFile(Path.of("mods", "ziggfreedcommon", "npc-placements.json"));
        NpcPlacementConfig.getInstance().mergePackLayer(Map.of());
        NpcPlacementConfig.getInstance().mergeOwnerLayer(Map.of());
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
    }

    private static void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : 1.0);
    }

    /** Fold the packs and apply the owner file exactly as the placement store's load handler does. */
    private void load() {
        NpcPlacementConfig.getInstance().mergePackLayer(AssetMergeAdapter.gate("NpcPlacements", packs,
                p -> NpcPlacementAsset.Requires.missingMod(p.getRequires())));
        NpcPlacementOverrides.getInstance().applyOwnerLayer();
    }

    private static String roleOf(String id) {
        NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(id);
        assertNotNull(placement, id + " should be folded");
        return placement.getIdentity().getRole();
    }

    @Test
    void withoutTheMmoItsOwnerBodiesAreDroppedWithOneLinePerKind() {
        mmoInstalled(false);

        load();

        NpcPlacementConfig config = NpcPlacementConfig.getInstance();
        assertNull(config.resolve("mmo_trainer"), "an owner body over a refused placement goes with it");
        assertNull(config.resolve("owner_mmo_guard"), "an owner placement gated on the missing mod is dropped");
        assertEquals("Zc_Other", roleOf("harvest_greeter"), "an owner body over a loaded placement still merges");
        assertEquals("Zc_Guard", roleOf("owner_guard"), "an ungated owner placement stays");
        assertEquals(List.of(
                "[zc] mod gate: NpcPlacements dropped 1 pack file(s) gated on a missing mod (" + MMO + ")",
                "[zc] mod gate: NpcPlacements dropped 2 owner override(s) gated on a missing mod (" + MMO + ")"),
                lines);
        for (String line : lines) {
            assertFalse(line.toLowerCase(Locale.ROOT).contains("mmo_"), "a drop line names no placement: " + line);
        }
    }

    @Test
    void withTheMmoEveryOwnerBodyLoadsAndMergesOverItsPackPlacement() {
        mmoInstalled(true);

        load();

        assertEquals("Zc_Other", roleOf("mmo_trainer"));
        assertNotNull(NpcPlacementConfig.getInstance().resolve("mmo_trainer").getRequires(),
                "it merged over the pack's placement and kept its gate");
        assertEquals("Zc_Guard", roleOf("owner_mmo_guard"));
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }

    // ==================== the owner's own gate on a placement the pack ships ungated (M295 fix round) ====================

    /** The owner file now gates the greeter, which the pack ships with no gate, and writes nothing else. */
    private void ownerGatesTheGreeter() throws IOException {
        Path file = NpcPlacementOverrides.getInstance().getFile();
        Files.writeString(file, "{ \"Harvest_Greeter\": { " + GATE + " } }", StandardCharsets.UTF_8);
        NpcPlacementOverrides.getInstance().load();
    }

    /**
     * The maintainer's ruling: an owner placement gated on a missing mod takes that whole id out, the
     * pack's ungated placement included, so nothing stands for it; it counts as refused and the owner line
     * counts it. Nothing names it.
     */
    @Test
    void withoutTheMmoAnOwnerGateOnAnUngatedPlacementTakesTheWholeIdOut() throws IOException {
        ownerGatesTheGreeter();
        mmoInstalled(false);

        load();

        NpcPlacementConfig config = NpcPlacementConfig.getInstance();
        assertNull(config.resolve("harvest_greeter"), "the pack's ungated placement does not stand in for it");
        assertFalse(config.all().containsKey("harvest_greeter"));
        assertFalse(config.rolesByPlacement().containsKey("harvest_greeter"), "so no audit reads its role");
        assertEquals(MMO, config.modGateRefused().get("harvest_greeter"), "it counts as refused");
        assertEquals(List.of(
                "[zc] mod gate: NpcPlacements dropped 1 pack file(s) gated on a missing mod (" + MMO + ")",
                "[zc] mod gate: NpcPlacements dropped 1 owner override(s) gated on a missing mod (" + MMO + ")"),
                lines);
    }

    @Test
    void withTheMmoTheOwnersGateMergesOverThePackPlacementAndKeepsIt() throws IOException {
        ownerGatesTheGreeter();
        mmoInstalled(true);

        load();

        assertEquals("Zc_Greeter", roleOf("harvest_greeter"), "it keeps the role the pack wrote");
        assertNotNull(NpcPlacementConfig.getInstance().resolve("harvest_greeter").getRequires(),
                "and the gate the owner added");
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }

    /**
     * A re-read decodes each body against the packs' answer again, never against what the last read left
     * (an id it took out reads as nothing at all), so the entry still merges over the pack's placement.
     */
    @Test
    void aReadAfterATakeOutStillDecodesTheEntryAgainstThePackPlacement() throws IOException {
        ownerGatesTheGreeter();
        mmoInstalled(false);
        load();
        assertNull(NpcPlacementConfig.getInstance().resolve("harvest_greeter"));

        mmoInstalled(true);
        load();

        assertEquals("Zc_Greeter", roleOf("harvest_greeter"), "the entry inherits the pack's role again");
    }
}
