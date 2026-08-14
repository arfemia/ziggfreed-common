package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@link WorldSelector}'s pure matcher: the two positive axes alone and combined, the exclusion
 * filter, and the codec's null-is-null contract. No engine, no balance data.
 */
class WorldSelectorMatchTest {

    private static final String INSTANCE_WORLD = "instance-Forgotten_Temple-8f2c1a";

    private static WorldSelector decode(String json) throws IOException {
        return WorldSelector.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    // ==================== The two positive axes ====================

    @Test
    void matchAxisAloneMatchesInline() {
        WorldSelector selector = WorldSelector.of(new String[]{"*Forgotten_Temple*"}, null, null);

        MatchRank rank = selector.match(INSTANCE_WORLD, null);

        assertNotNull(rank, "an inline pattern is the whole vocabulary - nothing else to declare");
        assertEquals(MatchRank.PARTIAL_BAND, rank.band());
        assertNull(selector.match("default", null),
                "an inline pattern must not match an unrelated world");
    }

    @Test
    void aBareWordUnderMatchIsAnExactWorldName() {
        // The load-bearing pin for every shipped file that targets the main world: authored bare,
        // "default" means the world CALLED default and no other. A contains reading here would
        // silently place that content in every instance world whose name contains the word.
        WorldSelector selector = WorldSelector.of(new String[]{"default"}, null, null);

        MatchRank rank = selector.match("default", null);
        assertNotNull(rank, "the world named default must match");
        assertEquals(MatchRank.EXACT_NAME_BAND, rank.band(), "a bare word is an EXACT name, not a contains");

        assertNull(selector.match("default_arena", null), "a longer name is a different world");
        assertNull(selector.match("instance-Default_Dungeon-9f3a", null),
                "an instance world merely containing the word is a different world");
        assertNotNull(selector.match("DEFAULT", null), "world-name matching stays case-insensitive");
    }

    @Test
    void gameplayConfigAxisAloneMatchesAndIsCaseInsensitive() {
        WorldSelector selector = WorldSelector.of(null, new String[]{"ForgottenTemple"}, null);

        assertEquals(MatchRank.gameplayConfig(), selector.match(INSTANCE_WORLD, "forgottentemple"));
        assertNull(selector.match(INSTANCE_WORLD, "Default"));
    }

    @Test
    void gameplayConfigOutranksAnExactNameMatch() {
        // The same world reached two ways: its authored machine key, and its literal name. The
        // machine key wins - it survives the instance being torn down and re-created.
        WorldSelector byConfig = WorldSelector.of(null, new String[]{"ForgottenTemple"}, null);
        WorldSelector byName = WorldSelector.of(new String[]{INSTANCE_WORLD}, null, null);

        MatchRank configRank = byConfig.match(INSTANCE_WORLD, "ForgottenTemple");
        MatchRank nameRank = byName.match(INSTANCE_WORLD, "ForgottenTemple");

        assertNotNull(configRank);
        assertNotNull(nameRank);
        assertEquals(MatchRank.EXACT_NAME_BAND, nameRank.band());
        assertTrue(configRank.isMoreSpecificThan(nameRank),
                "GameplayConfig is the top band, above an exact world-name match");
    }

    @Test
    void bothAxesTogetherKeepTheMoreSpecificRank() {
        WorldSelector selector = WorldSelector.of(new String[]{"*Forgotten_Temple*"},
                new String[]{"ForgottenTemple"}, null);

        assertEquals(MatchRank.gameplayConfig(), selector.match(INSTANCE_WORLD, "ForgottenTemple"),
                "the best rank across axes wins");
    }

    @Test
    void neitherAxisMatchingYieldsNull() {
        WorldSelector selector = WorldSelector.of(new String[]{"*Forgotten_Temple*"},
                new String[]{"ForgottenTemple"}, null);

        assertNull(selector.match("default", "Default"));
    }

    @Test
    void aBlankSelectorMatchesNothingAndNeverInventsADefault() {
        // Read sites apply their own default for an unauthored selector; the matcher must not.
        WorldSelector blank = new WorldSelector();
        assertTrue(blank.isBlank());
        assertNull(blank.match("default", "Default"));
    }

    // ==================== ExcludeMatch is a filter, not a complement ====================

    @Test
    void excludeMatchOnlyMatchesNothing() {
        WorldSelector selector = WorldSelector.of(null, null, new String[]{"*instance*"});

        assertTrue(selector.hasNoPositiveAxis());
        assertNull(selector.match("default", "Default"),
                "an ExcludeMatch-only selector is a filter with nothing to filter, so it matches nothing");
    }

    @Test
    void excludeMatchVetoesAnOtherwiseMatchingWorld() {
        WorldSelector selector = WorldSelector.of(new String[]{"*"}, null, new String[]{"*instance*"});

        assertNotNull(selector.match("default", null),
                "the catch-all applies where the excluded pattern does not reach");
        assertNull(selector.match(INSTANCE_WORLD, null),
                "a world whose name matches an excluded pattern is rejected even though Match hit");
    }

    @Test
    void excludeMatchUsesTheSamePatternGrammarAsMatch() {
        WorldSelector selector = WorldSelector.of(new String[]{"*"}, null, new String[]{"arena"});

        assertNull(selector.match("arena", null), "a bare exclusion is an exact name, like Match");
        assertNotNull(selector.match("arena_of_kings", null),
                "an exact exclusion must not swallow a longer name");
    }

    // ==================== Codec contract ====================

    @Test
    void absentKeysDecodeToNullNotToADefault() throws IOException {
        WorldSelector decoded = decode("{ \"Match\": [\"default\"] }");

        assertNotNull(decoded.getMatch());
        assertEquals(List.of("default"), List.of(decoded.getMatch()));
        assertNull(decoded.getGameplayConfig(), "an absent list must stay null - read sites own their defaults");
        assertNull(decoded.getExcludeMatch());
    }

    @Test
    void allThreeKeysDecode() throws IOException {
        WorldSelector decoded = decode("{ \"Match\": [\"*Forgotten_Temple*\"], "
                + "\"GameplayConfig\": [\"ForgottenTemple\"], \"ExcludeMatch\": [\"*Arena*\"] }");

        assertEquals(List.of("*Forgotten_Temple*"), List.of(decoded.getMatch()));
        assertEquals(List.of("ForgottenTemple"), List.of(decoded.getGameplayConfig()));
        assertEquals(List.of("*Arena*"), List.of(decoded.getExcludeMatch()));
    }

    @Test
    void aDecodedSelectorMatchesTheSameWayAsAJavaBuiltOne() throws IOException {
        WorldSelector decoded = decode("{ \"Match\": [\"*Forgotten_Temple*\"] }");
        WorldSelector built = WorldSelector.of(new String[]{"*Forgotten_Temple*"}, null, null);

        assertEquals(built.match(INSTANCE_WORLD, null), decoded.match(INSTANCE_WORLD, null));
    }
}
