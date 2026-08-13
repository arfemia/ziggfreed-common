package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@link WorldSelector}'s pure matcher: the three positive axes alone and combined, the
 * exclusion filter, and the codec's null-is-null contract. No engine, no balance data.
 */
class WorldSelectorMatchTest {

    private static final String INSTANCE_WORLD = "instance-Forgotten_Temple-8f2c1a";

    private static WorldSelector decode(String json) throws IOException {
        return WorldSelector.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    private static WorldNameIndex index(String name, MatchRank rank) {
        return WorldNameIndex.of(Map.of(name, rank));
    }

    // ==================== The three positive axes ====================

    @Test
    void nameAxisAloneMatchesAndInheritsTheUnderlyingRank() {
        WorldSelector selector = WorldSelector.of(new String[]{"forgotten_temple"}, null, null, null);
        MatchRank contributed = MatchRank.ofNamePattern(WorldNameMatcher.Pattern.parse("*forgotten_temple*"));

        MatchRank rank = selector.match(INSTANCE_WORLD, "ForgottenTemple",
                index("forgotten_temple", contributed));

        assertEquals(contributed, rank,
                "a name reference must inherit the rank of the pattern that actually matched");
    }

    @Test
    void matchAxisAloneMatchesInline() {
        WorldSelector selector = WorldSelector.of(null, new String[]{"*Forgotten_Temple*"}, null, null);

        MatchRank rank = selector.match(INSTANCE_WORLD, null, WorldNameIndex.EMPTY);

        assertNotNull(rank, "an inline pattern must work with no selector asset in play");
        assertEquals(MatchRank.PARTIAL_BAND, rank.band());
        assertNull(selector.match("default", null, WorldNameIndex.EMPTY),
                "an inline pattern must not match an unrelated world");
    }

    @Test
    void gameplayConfigAxisAloneMatchesAndIsCaseInsensitive() {
        WorldSelector selector = WorldSelector.of(null, null, new String[]{"ForgottenTemple"}, null);

        assertEquals(MatchRank.gameplayConfig(),
                selector.match(INSTANCE_WORLD, "forgottentemple", WorldNameIndex.EMPTY));
        assertNull(selector.match(INSTANCE_WORLD, "Default", WorldNameIndex.EMPTY));
    }

    @Test
    void gameplayConfigOutranksAnExactNameMatch() {
        // The same world reached two ways: its authored machine key, and its literal name. The
        // machine key wins - it survives the instance being torn down and re-created.
        WorldSelector byConfig = WorldSelector.of(null, null, new String[]{"ForgottenTemple"}, null);
        WorldSelector byName = WorldSelector.of(null, new String[]{INSTANCE_WORLD}, null, null);

        MatchRank configRank = byConfig.match(INSTANCE_WORLD, "ForgottenTemple", WorldNameIndex.EMPTY);
        MatchRank nameRank = byName.match(INSTANCE_WORLD, "ForgottenTemple", WorldNameIndex.EMPTY);

        assertNotNull(configRank);
        assertNotNull(nameRank);
        assertEquals(MatchRank.EXACT_NAME_BAND, nameRank.band());
        assertTrue(configRank.isMoreSpecificThan(nameRank),
                "GameplayConfig is the top band, above an exact world-name match");
    }

    @Test
    void bothAxesTogetherKeepTheMoreSpecificRank() {
        WorldSelector selector = WorldSelector.of(null, new String[]{"*Forgotten_Temple*"},
                new String[]{"ForgottenTemple"}, null);

        assertEquals(MatchRank.gameplayConfig(),
                selector.match(INSTANCE_WORLD, "ForgottenTemple", WorldNameIndex.EMPTY),
                "the best rank across axes wins");
    }

    @Test
    void neitherAxisMatchingYieldsNull() {
        WorldSelector selector = WorldSelector.of(new String[]{"forgotten_temple"},
                new String[]{"*Forgotten_Temple*"}, new String[]{"ForgottenTemple"}, null);

        assertNull(selector.match("default", "Default", WorldNameIndex.EMPTY));
    }

    @Test
    void aBlankSelectorMatchesNothingAndNeverInventsADefault() {
        // Read sites apply their own default for an unauthored selector; the matcher must not.
        WorldSelector blank = new WorldSelector();
        assertTrue(blank.isBlank());
        assertNull(blank.match("default", "Default", WorldNameIndex.EMPTY));
    }

    // ==================== ExcludeNames is a filter, not a complement ====================

    @Test
    void excludeNamesOnlyMatchesNothing() {
        WorldSelector selector = WorldSelector.of(null, null, null, new String[]{"instance"});

        assertTrue(selector.hasNoPositiveAxis());
        assertNull(selector.match("default", "Default", index("default", MatchRank.gameplayConfig())),
                "an ExcludeNames-only selector is a filter with nothing to filter, so it matches nothing");
    }

    @Test
    void excludeNamesVetoesAnOtherwiseMatchingWorld() {
        WorldSelector selector = WorldSelector.of(null, new String[]{"*"}, null, new String[]{"instance"});

        assertNotNull(selector.match("default", null, WorldNameIndex.EMPTY),
                "the catch-all applies where the excluded name is absent");
        assertNull(selector.match(INSTANCE_WORLD, null, index("instance", MatchRank.gameplayConfig())),
                "a world carrying an excluded name is rejected even though Match hit");
    }

    // ==================== Codec contract ====================

    @Test
    void absentKeysDecodeToNullNotToADefault() throws IOException {
        WorldSelector decoded = decode("{ \"Names\": [\"default\"] }");

        assertNotNull(decoded.getNames());
        assertEquals(List.of("default"), List.of(decoded.getNames()));
        assertNull(decoded.getMatch(), "an absent list must stay null - read sites own their defaults");
        assertNull(decoded.getGameplayConfig());
        assertNull(decoded.getExcludeNames());
    }

    @Test
    void allFourKeysDecode() throws IOException {
        WorldSelector decoded = decode("{ \"Names\": [\"forgotten_temple\"], \"Match\": [\"*Forgotten_Temple*\"], "
                + "\"GameplayConfig\": [\"ForgottenTemple\"], \"ExcludeNames\": [\"arena\"] }");

        assertEquals(List.of("forgotten_temple"), List.of(decoded.getNames()));
        assertEquals(List.of("*Forgotten_Temple*"), List.of(decoded.getMatch()));
        assertEquals(List.of("ForgottenTemple"), List.of(decoded.getGameplayConfig()));
        assertEquals(List.of("arena"), List.of(decoded.getExcludeNames()));
    }

    @Test
    void aDecodedSelectorMatchesTheSameWayAsAJavaBuiltOne() throws IOException {
        WorldSelector decoded = decode("{ \"Match\": [\"*Forgotten_Temple*\"] }");
        WorldSelector built = WorldSelector.of(null, new String[]{"*Forgotten_Temple*"}, null, null);

        assertEquals(built.match(INSTANCE_WORLD, null, WorldNameIndex.EMPTY),
                decoded.match(INSTANCE_WORLD, null, WorldNameIndex.EMPTY));
    }
}
