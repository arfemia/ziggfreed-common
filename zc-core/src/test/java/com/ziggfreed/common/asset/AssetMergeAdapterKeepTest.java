package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.ModGates;

/**
 * The keep-filter fold: a file the filter refuses never enters the layer, every other one does under its
 * lower-cased id, the mapper form drops the same files, and {@code refused} names exactly the ones left
 * out. Driven through the engine's own map, with its pack-loading door opened for the test, and a
 * stand-in asset carrying {@link PresenceRequiresCodec}'s block.
 */
class AssetMergeAdapterKeepTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String PACK = "Ziggfreed:SeasonsOfOrbis";

    /** A stand-in for any store's asset that carries the presence block. */
    static final class Probe implements JsonAsset<String> {
        String id;
        AssetExtraInfo.Data data;
        @Nullable PresenceRequiresCodec.Block requires;

        static final AssetBuilderCodec<String, Probe> CODEC = AssetBuilderCodec.builder(Probe.class, Probe::new,
                        Codec.STRING, (a, id) -> a.id = id, a -> a.id, (a, d) -> a.data = d, a -> a.data)
                .appendInherited(new KeyedCodec<>("Requires", PresenceRequiresCodec.CODEC, false),
                        (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
                .add()
                .build();

        @Override
        public String getId() {
            return id;
        }
    }

    /** The engine map with its pack-loading door opened for a test. */
    private static final class PackMap extends DefaultAssetMap<String, Probe> {

        PackMap load(String id, String json) throws IOException {
            Probe asset = Probe.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                    new AssetExtraInfo<>(new AssetExtraInfo.Data(Probe.class, id, null)));
            putAll(PACK, Probe.CODEC, Map.of(id, asset), Map.of(id, Path.of("SeasonsOfOrbis", id + ".json")),
                    Map.of(id, Set.of()));
            return this;
        }
    }

    private static final Predicate<Probe> LOADS_HERE = p -> PresenceRequiresCodec.passesModGate(p.requires);

    private PackMap map;

    @BeforeEach
    void twoFilesOneGated() throws IOException {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? 0.0 : 1.0);
        map = new PackMap()
                .load("Mmo_Skill_Job", "{ \"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
                        + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] } }")
                .load("Harvest_Feast_Job", "{}");
    }

    @AfterEach
    void restore() {
        ModGates.useProbeForTests(null);
    }

    @Test
    void aRefusedFileNeverEntersTheLayerAndRefusedNamesIt() {
        assertEquals(Set.of("harvest_feast_job"), AssetMergeAdapter.layer(map, LOADS_HERE).keySet());
        assertEquals(Set.of("mmo_skill_job"), AssetMergeAdapter.refused(map, LOADS_HERE));
    }

    @Test
    void theMapperFormDropsTheSameFiles() {
        Map<String, String> mapped = AssetMergeAdapter.layer(map, LOADS_HERE,
                (id, probe) -> id.toUpperCase(Locale.ROOT));

        assertEquals(Map.of("harvest_feast_job", "HARVEST_FEAST_JOB"), mapped);
    }

    @Test
    void withTheModInstalledNothingIsRefused() {
        ModGates.useProbeForTests(param -> 1.0);

        assertEquals(Set.of("mmo_skill_job", "harvest_feast_job"), AssetMergeAdapter.layer(map, LOADS_HERE).keySet());
        assertTrue(AssetMergeAdapter.refused(map, LOADS_HERE).isEmpty());
    }
}
