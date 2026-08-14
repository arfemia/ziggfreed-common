package com.ziggfreed.common.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.placement.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.NpcPlacementConfig;

/**
 * What a character is called, and what happens when nothing says.
 *
 * <p><b>Two halves, and only one of them is testable here.</b> Resolving a role's authored key walks
 * the engine's own NPC builder registry, which does not exist in a unit JVM - so every path that
 * ends at the engine answers null here, and these tests assert exactly that rather than pretending
 * otherwise. The live half (a spawned NPC's built role, and a {@code Variant} chain resolving a
 * {@code Compute}-bound key) is in-game smoke territory, matching the rest of this package's split.
 *
 * <p>What IS pinned offline is everything ABOVE the walk: which placement answers for a character,
 * which role that placement stands, the character-IS-its-role fallback, case, and the caching rule.
 * Those decide WHICH role gets asked, and asking the wrong one is a wrong name on screen rather than
 * a blank one, which is the failure this whole surface exists to make impossible.
 */
class NpcNamesTest {

    private static NpcPlacementAsset roled(String id, String role, String npcId, String... aliases) {
        return NpcPlacementAsset.of(id, null,
                NpcPlacementAsset.Identity.of(role, npcId, aliases.length == 0 ? null : aliases),
                null, null, null, null, null, null);
    }

    private static void loadPlacements(NpcPlacementAsset... placements) {
        Map<String, NpcPlacementAsset> layer = new LinkedHashMap<>();
        for (NpcPlacementAsset placement : placements) {
            layer.put(placement.getId(), placement);
        }
        NpcPlacementConfig.getInstance().mergePackLayer(layer);
    }

    @BeforeEach
    @AfterEach
    void reset() {
        NpcPlacementConfig.getInstance().mergePackLayer(Map.of());
        NpcNames.invalidate();
    }

    @Nested
    class TheLadder {

        @Test
        void aCharactersNameComesFromTheRoleThePlacementStands() {
            loadPlacements(roled("mmo_hub", "MMO_Hub", "adventurers_guide"));
            NpcNames.cacheForTests("MMO_Hub", "npcs.adventurers_guide.name");

            assertEquals("npcs.adventurers_guide.name", NpcNames.nameKeyOfPlacement("mmo_hub"));
            assertEquals("npcs.adventurers_guide.name", NpcNames.nameKeyFor("adventurers_guide"),
                    "the character id must reach the same authored key its own standing does, or the "
                            + "conversation header and the nameplate could disagree");
        }

        @Test
        void anIdNothingStandsUpIsReadAsARoleId() {
            NpcNames.cacheForTests("Kweebec_Elder", "npcs.Kweebec_Elder.name");

            assertEquals("npcs.Kweebec_Elder.name", NpcNames.nameKeyFor("Kweebec_Elder"),
                    "the character IS its role by default, so a name must resolve with no placement "
                            + "and no file anywhere");
        }

        @Test
        void anAliasResolvesTheStandingCharactersOwnRole() {
            loadPlacements(roled("temple", "MMO_Hub", "Mmo_Hub_Temple", "the_guide"));
            NpcNames.cacheForTests("MMO_Hub", "npcs.adventurers_guide.name");

            assertEquals("npcs.adventurers_guide.name", NpcNames.nameKeyFor("the_guide"),
                    "an alias is content aimed at this character, so it must reach the role standing "
                            + "there rather than looking for a role of its own");
        }

        @Test
        void aPlacementIsAskedBeforeTheRoleConvention() {
            loadPlacements(roled("shrine", "Sage_Orenna", "wandering_sage"));
            NpcNames.cacheForTests("Sage_Orenna", "npcs.Sage_Orenna.name");
            NpcNames.cacheForTests("wandering_sage", "npcs.some_other_role.name");

            assertEquals("npcs.Sage_Orenna.name", NpcNames.nameKeyFor("wandering_sage"),
                    "a character standing somewhere is named by the role standing there, never by a "
                            + "role that happens to share its id");
        }

        @Test
        void caseNeverDecidesWhetherANameResolves() {
            loadPlacements(roled("mmo_hub", "MMO_Hub", null));
            NpcNames.cacheForTests("MMO_Hub", "npcs.adventurers_guide.name");

            assertEquals("npcs.adventurers_guide.name", NpcNames.nameKeyOfRole("mmo_hub"));
            assertEquals("npcs.adventurers_guide.name", NpcNames.nameKeyOfPlacement("MMO_HUB"));
            assertEquals("npcs.adventurers_guide.name", NpcNames.nameKeyFor("mmo_hub"),
                    "the engine compares a role name without regard to case, so a capital letter "
                            + "apart must not be a blank header");
        }

        @Test
        void aPlacementThatNamesNoRoleNamesNobody() {
            loadPlacements(roled("broken", null, "some_character"));

            assertNull(NpcNames.nameKeyOfPlacement("broken"),
                    "it stands nobody up, so there is no role carrying a name to read");
        }
    }

    /**
     * The rule the whole surface rests on: a missing name is BLANK, never invented. A guessed key
     * renders as its own raw text and reads to a player as a name somebody chose, so a wrong one is
     * worse than none.
     */
    @Nested
    class NothingIsEverInvented {

        @Test
        void nothingNamesNobody() {
            assertNull(NpcNames.nameKeyFor(null));
            assertNull(NpcNames.nameKeyFor("   "));
            assertNull(NpcNames.nameKeyOfPlacement(null));
            assertNull(NpcNames.nameKeyOfPlacement("never_authored"));
            assertNull(NpcNames.nameKeyOfRole(null));
        }

        @Test
        void anUnknownCharacterGetsNoDerivedKeyAndNoPrettifiedWord() {
            loadPlacements(roled("shrine", "Sage_Orenna", null));

            assertNull(NpcNames.nameKeyFor("Sage_Orenna"),
                    "no npcs.<id>.name guess, no case-fold retry, no prettified id: a character whose "
                            + "role carries no key is a validator finding, not a header");
        }
    }

    /**
     * The engine-touching walk, asserted for the ONE thing that is knowable offline: it degrades to
     * null instead of throwing. It is assembled from the engine's own builder pieces rather than
     * copied from a first-party caller, so a wrong assumption must cost a blank name, never an
     * exception out of a page render or an audit loop.
     */
    @Nested
    class TheEngineHalf {

        @Test
        void theStaticWalkDegradesToNullWithNoServer() {
            assertNull(NpcNames.nameKeyOfRole("Any_Role_At_All"));
        }

        @Test
        void theRoleRegistryReportsThatItCannotBeAskedRatherThanAnswering() {
            assertFalse(NpcNames.canResolveNames(),
                    "an audit reading 'no key' as an answer here would report every placement on the "
                            + "server the moment it ran a millisecond too early");
        }
    }

    @Nested
    class TheCache {

        @Test
        void nothingNegativeIsRemembered() {
            assertNull(NpcNames.nameKeyOfRole("Late_Pack_Role"));
            NpcNames.cacheForTests("Late_Pack_Role", "npcs.Late_Pack_Role.name");

            assertEquals("npcs.Late_Pack_Role.name", NpcNames.nameKeyOfRole("Late_Pack_Role"),
                    "a pack registered later brings roles with it, so a remembered 'no' would outlive "
                            + "the reason for it");
        }

        @Test
        void invalidatingDropsWhatWasResolved() {
            NpcNames.cacheForTests("MMO_Hub", "npcs.adventurers_guide.name");
            assertEquals("npcs.adventurers_guide.name", NpcNames.nameKeyOfRole("MMO_Hub"));

            NpcNames.invalidate();
            assertNull(NpcNames.nameKeyOfRole("MMO_Hub"),
                    "a role hot-reload must not keep answering with the key it used to carry");
        }

        @Test
        void aReloadedPlacementNamesItsNewRoleOnTheNextLookup() {
            NpcNames.cacheForTests("First_Role", "npcs.first.name");
            NpcNames.cacheForTests("Second_Role", "npcs.second.name");

            loadPlacements(roled("post", "First_Role", null));
            assertEquals("npcs.first.name", NpcNames.nameKeyOfPlacement("post"));

            loadPlacements(roled("post", "Second_Role", null));
            assertEquals("npcs.second.name", NpcNames.nameKeyOfPlacement("post"),
                    "only the ROLE answer is cached, never which role a placement stands");
        }
    }

    @Nested
    class TheMessageTwin {

        @Test
        void aResolvedKeyBecomesAClientResolvedMessage() {
            NpcNames.cacheForTests("Kweebec_Elder", "npcs.Kweebec_Elder.name");

            assertNotNull(NpcNames.nameFor("Kweebec_Elder"),
                    "the server never resolves a name itself: it hands the client a key to resolve "
                            + "in its own locale");
        }

        @Test
        void noKeyMeansNoMessageRatherThanAnEmptyOne() {
            assertNull(NpcNames.nameFor("nobody_at_all"));
            assertNull(NpcNames.nameFor(null));
        }
    }
}
