package com.ziggfreed.common.world.gather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * A block family as a file writes it: decoded through its own codec, then asked which blocks it holds by their item's
 * native tags, their id, the family's exclusions and a block's own {@code ZigBlockFamily} tag. Pure: tag maps are
 * handed in as the engine would merge them.
 */
class BlockFamilyAssetTest {

    private static BlockFamilyAsset decode(String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(BlockFamilyAsset.class, id, null);
        return BlockFamilyAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    private static Map<String, String[]> tags(String... pairs) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1].split(","));
        }
        return out;
    }

    @Test
    void aFamilyHoldsWhatItsTagsOrNamesMatchAndNotWhatItExcludes() throws IOException {
        BlockFamilyAsset stone = decode("Stone", "{\"Match\": {\"Tags\": {\"Type\": [\"Rock\"]}},"
                + " \"Exclude\": {\"Tags\": {\"Family\": [\"Gem\"]}, \"Blocks\": [\"*_Ornate*\"]}}");
        assertTrue(stone.holds("Stone", "Rock_Stone", tags("Type", "Rock")));
        assertTrue(stone.holds("Stone", "Rock_Basalt", tags("type", "rock")), "keys and values ignore case");
        assertFalse(stone.holds("Stone", "Rock_Gem_Ruby", tags("Type", "Rock", "Family", "Gem")));
        assertFalse(stone.holds("Stone", "Rock_Stone_Brick_Ornate", tags("Type", "Rock")));
        assertFalse(stone.holds("Stone", "Soil_Dirt", tags("Type", "Soil")));
        assertFalse(stone.holds("Stone", "Rock_Stone", null), "a block with no tags meets no tag");

        BlockFamilyAsset hard = decode("Hard_Stone", "{\"Match\": {\"Blocks\": [\"Rock_Basalt*\", \"Rock_Slate*\"]}}");
        assertTrue(hard.holds("Hard_Stone", "Rock_Basalt_Cobble", null));
        assertFalse(hard.holds("Hard_Stone", "Rock_Stone", tags("Type", "Rock")));
    }

    @Test
    void aBlockNamingTheFamilyItselfIsInItAndASwitchedOffFamilyHoldsNothing() throws IOException {
        BlockFamilyAsset ore = decode("Ore", "{\"Match\": {\"Tags\": {\"Type\": [\"Ore\"]}}}");
        assertTrue(ore.holds("Ore", "Mymod_Strange_Rock", tags("ZigBlockFamily", "Ore,Stone")),
                "another mod opts its block in from its own item file");
        BlockFamilyAsset empty = decode("Mine", "{}");
        assertTrue(empty.holds("Mine", "Mymod_Block", tags("ZigBlockFamily", "mine")));
        assertFalse(empty.holds("Mine", "Rock_Stone", tags("Type", "Rock")), "no Match holds only opt-ins");
        BlockFamilyAsset off = decode("Ore", "{\"Match\": {\"Tags\": {\"Type\": [\"Ore\"]}}, \"Enabled\": false}");
        assertFalse(off.holds("Ore", "Ore_Iron_Stone", tags("Type", "Ore", "ZigBlockFamily", "Ore")));
    }

    @Test
    void aBlocksMembershipNamesEveryFamilyHoldingIt() throws IOException {
        Map<String, BlockFamilyAsset> families = new LinkedHashMap<>();
        families.put("stone", decode("Stone", "{\"Match\": {\"Tags\": {\"Type\": [\"Rock\"]}}}"));
        families.put("hard_stone", decode("Hard_Stone", "{\"Match\": {\"Blocks\": [\"Rock_Basalt*\"]}}"));
        families.put("thrift", decode("Thrift", "{\"Match\": {\"Tags\": {\"Type\": [\"Rock\", \"Wood\"]}}}"));
        assertEquals(Set.of("stone", "hard_stone", "thrift"),
                BlockFamilyConfig.membership("Rock_Basalt", tags("Type", "Rock"), families));
        assertEquals(Set.of(), BlockFamilyConfig.membership("Soil_Dirt", tags("Type", "Soil"), families));
    }
}
