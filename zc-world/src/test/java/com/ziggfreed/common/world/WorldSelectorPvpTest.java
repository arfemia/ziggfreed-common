package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@link WorldSelector}'s {@code Pvp} key: it decodes, filters the other axes by the world's PvP switch, stands on its
 * own at the catch-all's rank, and never matches a world whose switch cannot be told. Pure: the switch is passed in.
 */
class WorldSelectorPvpTest {

    private static WorldSelector decode(String json) throws IOException {
        return WorldSelector.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void pvpAloneReachesEveryWorldWhoseSwitchAgreesAtTheCatchAllRank() throws IOException {
        WorldSelector selector = decode("{ \"Pvp\": true }");

        assertFalse(selector.hasNoPositiveAxis(), "a written Pvp stands on its own");
        MatchRank rank = selector.match("arena", null, true);
        assertNotNull(rank);
        assertEquals(MatchRank.ANY_BAND, rank.band());
        assertNull(selector.match("arena", null, false), "a world with PvP off is not a PvP world");
        assertNull(selector.match("arena", null, null), "a switch that cannot be told matches nothing");
    }

    @Test
    void pvpFiltersTheNameAxisAndKeepsItsRank() throws IOException {
        WorldSelector selector = decode("{ \"Match\": [ \"*Arena*\" ], \"Pvp\": false }");

        MatchRank rank = selector.match("north_arena_2", null, false);
        assertNotNull(rank);
        assertEquals(MatchRank.PARTIAL_BAND, rank.band(), "the name axis keeps its own rank under the filter");
        assertNull(selector.match("north_arena_2", null, true));
        assertNull(selector.match("default", null, false), "the filter never widens the name axis");
    }

    @Test
    void anUnwrittenPvpPlaysNoPart() throws IOException {
        WorldSelector selector = decode("{ \"Match\": [ \"default\" ] }");

        assertNull(selector.getPvp());
        assertNotNull(selector.match("default", null, true));
        assertNotNull(selector.match("default", null, false));
        assertNotNull(selector.match("default", null, null));
    }

    @Test
    void droppingThePlacementAxisKeepsThePvpFilter() {
        WorldSelector selector = WorldSelector.of(new String[]{"*"}, null, null, new String[]{"Some_Placement"})
                .withPvp(Boolean.TRUE);

        assertEquals(Boolean.TRUE, selector.withoutPlacement().getPvp());
    }
}
