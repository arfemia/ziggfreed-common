package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * A Factors file's three traits decode, read back through {@link FactorTraits}, and compose across files: the
 * first file in id order authoring a leaf wins it, the most volatile word wins {@code Volatility}, and an
 * unknown word reads as {@code Moment} and is named by the audit.
 */
class FactorTraitsTest {

    private static DerivedFactorAsset decode(String id, String json) throws IOException {
        return DerivedFactorAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class, id, null)));
    }

    @Test
    void theThreeTraitsDecodeAndReadBack() throws IOException {
        DerivedFactorAsset file = decode("Yourmod_Rank", "{ \"Factor\": \"yourmod:rank\", \"ParamDataSet\": "
                + "\"yourmod:ranks\", \"AbsentReads\": 0, \"Volatility\": \"player\" }");

        FactorTraits traits = FactorTraits.of("YourMod:Rank", Map.of("Yourmod_Rank", file));

        assertEquals("yourmod:ranks", traits.paramDataSet());
        assertEquals(0.0, traits.absentReads());
        assertEquals(Volatility.PLAYER, traits.volatility(), "the word is matched without regard to case");
        assertTrue(DerivedFactorValidator.validateAssets(Map.of("Yourmod_Rank", file), null).isEmpty(),
                "a file carrying traits alone is a valid overlay");
    }

    @Test
    void severalFilesComposeAndTheMostVolatileWordWins() throws IOException {
        DerivedFactorAsset first = decode("A_Rank", "{ \"Factor\": \"yourmod:rank\", \"AbsentReads\": 0, "
                + "\"Volatility\": \"Server\" }");
        DerivedFactorAsset second = decode("B_Rank", "{ \"Factor\": \"yourmod:rank\", \"AbsentReads\": 5, "
                + "\"ParamDataSet\": \"yourmod:ranks\", \"Volatility\": \"Player\" }");

        FactorTraits traits = FactorTraits.of("yourmod:rank", Map.of("A_Rank", first, "B_Rank", second));

        assertEquals(0.0, traits.absentReads(), "the first file in id order authoring it wins the leaf");
        assertEquals("yourmod:ranks", traits.paramDataSet());
        assertEquals(Volatility.PLAYER, traits.volatility(), "no file can talk a gate into keeping more");
    }

    @Test
    void anUnknownWordReadsAsMomentAndIsNamed() throws IOException {
        DerivedFactorAsset file = decode("Yourmod_Rank", "{ \"Factor\": \"yourmod:rank\", \"Volatility\": \"Daily\" }");

        assertEquals(Volatility.MOMENT, FactorTraits.of("yourmod:rank", Map.of("Yourmod_Rank", file)).volatility());
        assertEquals("UNKNOWN_VOLATILITY",
                DerivedFactorValidator.validateAssets(Map.of("Yourmod_Rank", file), null).get(0).code());
    }

    @Test
    void aFactorNoFileDescribesReadsNone() {
        FactorTraits traits = FactorTraits.of("yourmod:nothing", Map.of());

        assertNull(traits.paramDataSet());
        assertNull(traits.absentReads());
        assertEquals(Volatility.MOMENT, traits.volatility());
    }
}
