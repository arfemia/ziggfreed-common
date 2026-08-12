package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@link WorldSelectorAsset}'s decode contract plus the two structural files common ships:
 * they must parse, and they must grant the names every other selector-aware file is written
 * against.
 */
class WorldSelectorAssetTest {

    private static final Path SHIPPED =
            Path.of("src", "main", "resources", "Server", "ZiggfreedCommon", "WorldSelectors");

    private static WorldSelectorAsset decode(String json) throws IOException {
        return WorldSelectorAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    private static WorldSelectorDef decodeFile(String fileName) throws IOException {
        Path path = SHIPPED.resolve(fileName);
        assertTrue(Files.isRegularFile(path), "missing shipped selector: " + path.toAbsolutePath());
        WorldSelectorAsset asset = decode(Files.readString(path));
        // The engine keys an asset by its filename; mirror that so the def carries the real id.
        return asset.toDef(fileName.replace(".json", "").toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void absentKeysDecodeToNull() throws IOException {
        WorldSelectorAsset asset = decode("{ \"Names\": [\"primary\"] }");

        assertEquals(List.of("primary"), List.of(asset.getNames()));
        assertNull(asset.getMatch());
        assertNull(asset.getGameplayConfig());
    }

    @Test
    void aShippedCommentKeyDoesNotBreakTheDecode() throws IOException {
        WorldSelectorAsset asset = decode(
                "{ \"$Comment\": \"a tip for the server owner\", \"Names\": [\"primary\"], "
                        + "\"Match\": [\"default\"] }");

        assertNotNull(asset.getNames());
        assertEquals(List.of("default"), List.of(asset.getMatch()));
    }

    @Test
    void theDefMapsEveryAuthoredAxis() throws IOException {
        WorldSelectorDef def = decode("{ \"Names\": [\"Forgotten_Temple\", \"instance\"], "
                + "\"Match\": [\"*Forgotten_Temple*\"], \"GameplayConfig\": [\"ForgottenTemple\"] }")
                .toDef("mmo_forgotten_temple");

        assertEquals(List.of("forgotten_temple", "instance"), def.names(), "names are lower-cased");
        assertEquals("mmo_forgotten_temple", def.id());
        assertNotNull(def.rankFor("instance-Forgotten_Temple-8f2c1a", null));
        assertEquals(MatchRank.gameplayConfig(), def.rankFor("whatever", "ForgottenTemple"));
    }

    // ==================== The shipped structural files ====================

    @Test
    void shippedPrimaryNamesTheStockWorld() throws IOException {
        WorldSelectorDef def = decodeFile("Zc_Primary.json");

        assertEquals(List.of("primary"), def.names());
        assertTrue(WorldIdentity.resolve("default", null, List.of(def)).has("primary"));
        assertTrue(WorldIdentity.resolve("instance-Forgotten_Temple-8f2c1a", "ForgottenTemple",
                List.of(def)).isEmpty(), "'primary' must not leak into instance worlds");
    }

    @Test
    void shippedAnyNamesEveryWorldAtTheLowestRank() throws IOException {
        WorldSelectorDef any = decodeFile("Zc_Any.json");
        WorldSelectorDef primary = decodeFile("Zc_Primary.json");

        assertEquals(List.of("any"), any.names());
        WorldNameIndex index = WorldIdentity.resolve("default", null, List.of(any, primary));
        assertTrue(index.has("any"));
        assertTrue(index.has("primary"));
        assertTrue(index.rankOf("primary").isMoreSpecificThan(index.rankOf("any")),
                "the catch-all must lose to a real name, or it could not serve as a baseline");
        assertTrue(WorldIdentity.resolve("instance-KweebecNightmare_Chase-77aa", null, List.of(any))
                .has("any"));
    }

    @Test
    void theShippedFilesPassValidation() throws IOException {
        assertTrue(WorldSelectorValidator.validateAll(
                List.of(decodeFile("Zc_Primary.json"), decodeFile("Zc_Any.json"))).isEmpty());
    }

    // ==================== The config fold ====================

    @Test
    void theConfigFoldsAPackLayerAndResolvesById() throws IOException {
        WorldSelectorConfig config = WorldSelectorConfig.getInstance();
        WorldSelectorDef def = decodeFile("Zc_Primary.json");
        try {
            config.mergePackLayer(Map.of("zc_primary", def));

            assertNotNull(config.resolve("zc_primary"));
            assertNotNull(config.resolve("ZC_PRIMARY"), "ids fold case-insensitively");
            assertTrue(config.audit().isEmpty());
        } finally {
            // The config is a process-wide singleton; leave it clean for any other test.
            config.mergePackLayer(Map.of());
        }
    }
}
