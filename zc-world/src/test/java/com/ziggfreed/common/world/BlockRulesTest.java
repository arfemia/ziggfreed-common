package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.protocol.GameMode;

/**
 * The {@code ZigBlockRules} gameplay config group: its leaf reads as written and unauthored reads false
 * (the codec builds at class init, where a malformed key would throw), and the refusal it drives spares a
 * creative-mode player alone. What the systems do to a live break is smoke.
 */
class BlockRulesTest {

    @Test
    void theLeafReadsAsWrittenAndDefaultsOff() throws IOException {
        assertTrue(decode("{ \"CreativeOnlyBreaking\": true }").creativeOnlyBreaking());
        assertFalse(decode("{ \"CreativeOnlyBreaking\": false }").creativeOnlyBreaking());
        assertFalse(decode("{}").creativeOnlyBreaking(), "unauthored means everybody the engine allows breaks");
        assertFalse(BlockRules.creativeOnlyBreaking(null), "no gameplay config means no rule");
        assertNotNull(BlockRules.CODEC.toSchema(new SchemaContext()).getProperties().get("CreativeOnlyBreaking"),
                "the schema the Asset Editor loads declares the leaf");
    }

    @Test
    void onlyACreativePlayerBreaksUnderTheRule() {
        assertFalse(BlockRules.refusesBreaker(true, GameMode.Creative));
        assertTrue(BlockRules.refusesBreaker(true, GameMode.Adventure));
        assertTrue(BlockRules.refusesBreaker(true, null), "an NPC or an explosion is no creative player");
        assertFalse(BlockRules.refusesBreaker(false, GameMode.Adventure), "without the rule the engine decides");
        assertFalse(BlockRules.refusesBreaker(false, null));
    }

    private static BlockRules decode(String json) throws IOException {
        return BlockRules.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }
}
