package com.ziggfreed.common.loot.trigger;

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
import com.ziggfreed.common.asset.PresenceRequiresCodec;
import com.ziggfreed.common.factor.ModGates;

/**
 * The bonus-row owner file follows the mod gate, driven exactly as the row store's load handler drives it
 * (the gated pack fold, then {@link BonusRowOwnerLayers#reload()}): an owner gate on a row the pack ships
 * without one takes that whole row out where the mod is missing, so the table never rolls the pack's
 * ungated version; a retune of a row the gate refused goes with it; and an owner row gated on the missing
 * mod is dropped. One counted owner line, no row named. With the mod, the owner's gate merges over the
 * pack's row, keeping what the pack wrote, and keeps the gate it adds.
 */
class BonusRowOwnerModGateTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String GATE = "\"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
            + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] }";
    private static final String GEODE = "\"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"*Geode*\" }";
    private static final String BONES = "\"When\": { \"Kind\": \"KillMob\", \"Match\": \"Skeleton_*\" }";

    /** The engine map with its pack-loading door opened, as the load event hands it over. */
    private static final class PackMap extends DefaultAssetMap<String, BonusRowAsset> {

        PackMap load(String id, String json) throws IOException {
            BonusRowAsset asset = BonusRowAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json),
                    null, new AssetExtraInfo<>(new AssetExtraInfo.Data(BonusRowAsset.class, id, null)));
            putAll("Ziggfreed:SeasonsOfOrbis", BonusRowAsset.CODEC, Map.of(id, asset),
                    Map.of(id, Path.of("SeasonsOfOrbis", id + ".json")), Map.of(id, Set.of()));
            return this;
        }
    }

    @TempDir
    Path ownerDir;

    private final List<String> lines = new ArrayList<>();
    private PackMap packs;

    @BeforeEach
    void anUngatedAndAGatedRowAndAnOwnerFileOverBoth() throws IOException {
        BonusRowOwnerLayers.setDirectory(ownerDir);
        ModGates.reportIntoForTests(lines::add);
        packs = new PackMap()
                .load("Harvest_Geode", "{ " + GEODE + " }")
                .load("Mmo_Bones", "{ " + GATE + ", " + BONES + " }");
        Files.writeString(BonusRowOwnerLayers.file(), "{"
                + " \"Harvest_Geode\": { " + GATE + " },"
                + " \"Mmo_Bones\": { \"Enabled\": true },"
                + " \"Owner_Mmo_Row\": { " + GATE + ", " + BONES + " } }", StandardCharsets.UTF_8);
    }

    @AfterEach
    void reset() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of());
        BonusRowConfig.getInstance().mergeOwnerLayer(Map.of());
        BonusRowOwnerLayers.setDirectory(BonusRowOwnerLayers.DEFAULT_DIRECTORY);
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
    }

    private static void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : 1.0);
    }

    /** The row store's load handler. */
    private void load() {
        BonusRowConfig.getInstance().mergePackLayer(AssetMergeAdapter.gate("BonusRows", packs,
                r -> PresenceRequiresCodec.missingMod(r.getRequires())));
        BonusRowOwnerLayers.reload();
    }

    @Test
    void withoutTheMmoTheOwnersGateTakesTheUngatedRowOutOfTheTable() {
        mmoInstalled(false);

        load();

        BonusRowConfig config = BonusRowConfig.getInstance();
        assertNull(config.resolve("harvest_geode"), "the owner's gate takes the pack's ungated row out");
        assertNull(config.bestFor(BonusMoment.BREAK_BLOCK, "Rock_Geode_Cursed"), "so the table never rolls it");
        assertEquals(MMO, config.modGateRefused().get("harvest_geode"), "it counts as refused");
        assertNull(config.resolve("mmo_bones"), "a retune of a refused row goes with it");
        assertNull(config.resolve("owner_mmo_row"), "an owner row gated on the missing mod is dropped");
        assertNull(config.bestFor(BonusMoment.KILL_MOB, "Skeleton_Burnt_Archer"));
        assertEquals(List.of(
                "[zc] mod gate: BonusRows dropped 1 pack file(s) gated on a missing mod (" + MMO + ")",
                "[zc] mod gate: BonusRows dropped 3 owner override(s) gated on a missing mod (" + MMO + ")"), lines);
        for (String line : lines) {
            assertFalse(line.toLowerCase(Locale.ROOT).contains("harvest_geode"), "a drop line names no row: " + line);
        }
    }

    @Test
    void withTheMmoTheOwnersGateMergesOverThePackRowAndKeepsIt() {
        mmoInstalled(true);

        load();

        BonusRowAsset merged = BonusRowConfig.getInstance().resolve("harvest_geode");
        assertNotNull(merged, "the owner's entry is in force");
        assertNotNull(merged.getRequires(), "with the gate it adds");
        BonusRow row = BonusRowConfig.getInstance().bestFor(BonusMoment.BREAK_BLOCK, "Rock_Geode_Cursed");
        assertNotNull(row, "and the pack's moment and pattern, which it left alone");
        assertEquals("harvest_geode", row.sourceId());
        assertNotNull(BonusRowConfig.getInstance().resolve("owner_mmo_row"));
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }
}
