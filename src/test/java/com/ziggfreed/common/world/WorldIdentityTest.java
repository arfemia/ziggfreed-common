package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.world.WorldNameMatcher.Pattern;

/**
 * {@link WorldIdentity}'s pure resolver: which names a world earns, from which axis, and the
 * many-to-many union across two assets contributing one name. The engine-facing
 * {@code indexFor(World)} is the same resolver plus a cache, so this covers the decision core.
 */
class WorldIdentityTest {

    private static final String INSTANCE_WORLD = "instance-Forgotten_Temple-8f2c1a";

    private static WorldSelectorDef def(String id, String[] names, String[] match, String[] gameplayConfig) {
        return new WorldSelectorDef(id, names, match, gameplayConfig);
    }

    @Test
    void nameAxisOnly() {
        WorldNameIndex index = WorldIdentity.resolve("default", null,
                List.of(def("zc_primary", new String[]{"primary"}, new String[]{"default"}, null)));

        assertEquals(Set.of("primary"), index.names());
        assertEquals(MatchRank.EXACT_NAME_BAND, index.rankOf("primary").band());
    }

    @Test
    void gameplayConfigAxisOnly() {
        WorldNameIndex index = WorldIdentity.resolve(INSTANCE_WORLD, "ForgottenTemple",
                List.of(def("mmo_forgotten_temple", new String[]{"forgotten_temple"}, null,
                        new String[]{"ForgottenTemple"})));

        assertEquals(Set.of("forgotten_temple"), index.names());
        assertEquals(MatchRank.gameplayConfig(), index.rankOf("forgotten_temple"),
                "the uuid-free machine key is the top band");
    }

    @Test
    void bothAxesKeepTheMoreSpecificRank() {
        WorldNameIndex index = WorldIdentity.resolve(INSTANCE_WORLD, "ForgottenTemple",
                List.of(def("mmo_forgotten_temple", new String[]{"forgotten_temple"},
                        new String[]{"*Forgotten_Temple*"}, new String[]{"ForgottenTemple"})));

        assertEquals(MatchRank.gameplayConfig(), index.rankOf("forgotten_temple"));
    }

    @Test
    void neitherAxisMatchingYieldsNoNames() {
        WorldNameIndex index = WorldIdentity.resolve("default", "Default",
                List.of(def("mmo_forgotten_temple", new String[]{"forgotten_temple"},
                        new String[]{"*Forgotten_Temple*"}, new String[]{"ForgottenTemple"})));

        assertTrue(index.names().isEmpty());
        assertNull(index.rankOf("forgotten_temple"));
        assertFalse(index.has("forgotten_temple"));
    }

    // ==================== The many-to-many union ====================

    @Test
    void twoAssetsContributingOneNameBothApply() {
        // The asset id is a pure address, so two mods can each ship a file feeding "primary".
        // A world must earn the name from EITHER, or a colliding filename would silently win.
        List<WorldSelectorDef> pool = List.of(
                def("zc_primary", new String[]{"primary"}, new String[]{"default"}, null),
                def("mmo_primary", new String[]{"primary"}, new String[]{"overworld"}, null));

        assertTrue(WorldIdentity.resolve("default", null, pool).has("primary"),
                "the first contributor's pattern must grant the name");
        assertTrue(WorldIdentity.resolve("overworld", null, pool).has("primary"),
                "the second contributor's pattern must grant the same name");
        assertFalse(WorldIdentity.resolve("arena", null, pool).has("primary"));
    }

    @Test
    void aUnionedNameKeepsTheMostSpecificContributingRank() {
        List<WorldSelectorDef> pool = List.of(
                def("broad", new String[]{"temple"}, new String[]{"*"}, null),
                def("precise", new String[]{"temple"}, null, new String[]{"ForgottenTemple"}));

        MatchRank rank = WorldIdentity.resolve(INSTANCE_WORLD, "ForgottenTemple", pool).rankOf("temple");

        assertEquals(MatchRank.gameplayConfig(), rank,
                "a precise contributor must not be masked by a catch-all contributor");
    }

    @Test
    void oneAssetContributingSeveralNamesGrantsThemAll() {
        WorldNameIndex index = WorldIdentity.resolve(INSTANCE_WORLD, null,
                List.of(def("mmo_forgotten_temple", new String[]{"forgotten_temple", "instance"},
                        new String[]{"*Forgotten_Temple*"}, null)));

        assertEquals(Set.of("forgotten_temple", "instance"), index.names());
        assertEquals(index.rankOf("forgotten_temple"), index.rankOf("instance"),
                "both names come from the same matching pattern, so they rank the same");
    }

    @Test
    void namesAreCaseInsensitiveOnBothSides() {
        WorldNameIndex index = WorldIdentity.resolve("Default", null,
                List.of(def("zc_primary", new String[]{"Primary"}, new String[]{"DEFAULT"}, null)));

        assertTrue(index.has("primary"));
        assertTrue(index.has("PRIMARY"));
        assertNotNull(index.rankOf(" primary "));
    }

    @Test
    void anEmptyPoolResolvesToTheEmptyIndex() {
        assertTrue(WorldIdentity.resolve("default", "Default", List.of()).isEmpty());
    }

    // ==================== The name is a shorthand for the pattern ====================

    @Test
    void aNamedSelectorAndAnInlinePatternProduceTheSameRank() {
        WorldSelectorDef contributor = def("mmo_forgotten_temple", new String[]{"forgotten_temple"},
                new String[]{"*Forgotten_Temple*"}, null);
        WorldNameIndex index = WorldIdentity.resolve(INSTANCE_WORLD, null, List.of(contributor));

        MatchRank viaName = WorldSelector.of(new String[]{"forgotten_temple"}, null, null, null)
                .match(INSTANCE_WORLD, null, index);
        MatchRank viaInline = WorldSelector.of(null, new String[]{"*Forgotten_Temple*"}, null, null)
                .match(INSTANCE_WORLD, null, index);

        assertEquals(viaInline, viaName,
                "a selector name is a shorthand for its patterns, so both sort in one ordering");
        assertEquals(MatchRank.ofNamePattern(Pattern.parse("*Forgotten_Temple*")), viaName);
    }
}
