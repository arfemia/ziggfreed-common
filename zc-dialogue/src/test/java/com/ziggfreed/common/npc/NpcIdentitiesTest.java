package com.ziggfreed.common.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.placement.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.NpcPlacementConfig;

/**
 * Who an NPC is, and which ids it answers to.
 *
 * <p>These are the rules content depends on without ever seeing them: a quest step resolves at a
 * character or it does not, and there is no error either way - the step simply never completes. So
 * the ladder's precedence, the primary-versus-alias asymmetry, and the reverse lookup a waypoint
 * reads are all pinned here rather than left to the one place they are implemented.
 *
 * <p>The rungs that need a running server (an NPC entity's role, native group membership) are
 * excluded on purpose; what is testable offline is the whole of the placement half plus the naming
 * convention, which is where every authored answer comes from.
 */
class NpcIdentitiesTest {

    private static NpcPlacementAsset placement(String id, String npcId, String... aliases) {
        return NpcPlacementAsset.of(id, null,
                NpcPlacementAsset.Identity.of(null, null, null, null, null, npcId,
                        aliases.length == 0 ? null : aliases),
                null, null, null, null, null, null);
    }

    private static void loadPlacements(NpcPlacementAsset... placements) {
        Map<String, NpcPlacementAsset> layer = new LinkedHashMap<>();
        for (NpcPlacementAsset placement : placements) {
            layer.put(placement.getId(), placement);
        }
        NpcPlacementConfig.getInstance().mergePackLayer(layer);
    }

    private static void loadIdentities(NpcIdentityAsset... overlays) {
        Map<String, NpcIdentityAsset> layer = new LinkedHashMap<>();
        for (NpcIdentityAsset overlay : overlays) {
            layer.put(overlay.getId(), overlay);
        }
        NpcIdentityConfig.getInstance().mergePackLayer(layer);
    }

    @BeforeEach
    @AfterEach
    void clearBothPools() {
        NpcPlacementConfig.getInstance().mergePackLayer(Map.of());
        NpcIdentityConfig.getInstance().mergePackLayer(Map.of());
    }

    @Nested
    class TheLadder {

        @Test
        void aPlacementsAuthoredIdIsWhoStandsThere() {
            loadPlacements(placement("mmo_hub", "adventurers_guide"));
            assertEquals("adventurers_guide", NpcIdentities.npcIdOfPlacement("mmo_hub"));
        }

        @Test
        void aPlacementThatAuthorsNoIdAnswersToItsOwnPlacementId() {
            loadPlacements(placement("guide_wilds", null));
            assertEquals("guide_wilds", NpcIdentities.npcIdOfPlacement("guide_wilds"),
                    "putting an NPC somewhere must be enough to make it nameable by content");
        }

        @Test
        void aPlacementNothingLoadedNamesIsNobody() {
            assertNull(NpcIdentities.npcIdOfPlacement("never_authored"));
            assertNull(NpcIdentities.npcIdOfPlacement(null));
            assertNull(NpcIdentities.npcIdOfPlacement("  "));
        }

        @Test
        void aRoleWithNoOverlayIsItsOwnNameInLowerCase() {
            assertEquals("kweebec_elder", NpcIdentities.npcIdOfRole("Kweebec_Elder"),
                    "the convention is the whole point: most NPCs need no file at all");
        }

        @Test
        void anOverlayOnTheRoleWinsOverTheConvention() {
            loadIdentities(NpcIdentityAsset.of("elder", "Kweebec_Elder", null, "village_elder", null));
            assertEquals("village_elder", NpcIdentities.npcIdOfRole("Kweebec_Elder"));
        }

        @Test
        void anOverlayIsFoundWhateverCaseTheRoleIsSpelledIn() {
            loadIdentities(NpcIdentityAsset.of("elder", "kweebec_elder", null, "village_elder", null));
            assertEquals("village_elder", NpcIdentities.npcIdOfRole("KWEEBEC_ELDER"),
                    "the engine matches a role name without regard to case, so this must too");
        }

        @Test
        void twoOverlaysClaimingOneRoleResolveToTheAlphabeticallyFirstFile() {
            loadIdentities(NpcIdentityAsset.of("zzz_late", "Kweebec_Elder", null, "late_answer", null),
                    NpcIdentityAsset.of("aaa_early", "Kweebec_Elder", null, "early_answer", null));
            assertEquals("early_answer", NpcIdentities.npcIdOfRole("Kweebec_Elder"),
                    "a collision must resolve the same way on every restart");
        }

        @Test
        void anOverlayWithNoIdSaysNothingAndTheConventionStands() {
            loadIdentities(NpcIdentityAsset.of("empty", "Kweebec_Elder", null, null, null));
            assertEquals("kweebec_elder", NpcIdentities.npcIdOfRole("Kweebec_Elder"));
        }
    }

    @Nested
    class TheAnswerSet {

        @Test
        void aPlacementAnswersToItsPrimaryFirstThenItsAliasesInAuthoredOrder() {
            loadPlacements(placement("mmo_hub", "adventurers_guide", "mmo_hub_guide", "town_crier"));
            assertEquals(List.of("adventurers_guide", "mmo_hub_guide", "town_crier"),
                    List.copyOf(NpcIdentities.answerSetOf("mmo_hub")));
        }

        @Test
        void authoredCaseSurvivesButMembershipIgnoresIt() {
            loadPlacements(placement("mmo_hub", "Adventurers_Guide"));
            assertTrue(NpcIdentities.answerSetOf("mmo_hub").contains("Adventurers_Guide"),
                    "content matching may be case-sensitive, so the authored spelling must survive");
            assertTrue(NpcIdentities.answersTo("mmo_hub", "adventurers_guide"));
            assertTrue(NpcIdentities.primaryAnswersTo("Adventurers_Guide", "ADVENTURERS_GUIDE"));
        }

        @Test
        void anAliasIsOnlyWhatAPlacementRespondsToNeverWhatItIs() {
            loadPlacements(placement("guide_wilds", "guide_wilds", "adventurers_guide"));
            assertEquals("guide_wilds", NpcIdentities.npcIdOfPlacement("guide_wilds"));
            assertEquals(List.of("adventurers_guide"), NpcIdentities.aliasesOf("guide_wilds"));
            assertTrue(NpcIdentities.primaryAnswersTo("guide_wilds", "adventurers_guide"),
                    "content aimed at the shared name resolves at this placement");
            assertFalse(NpcIdentities.primaryAnswersTo("adventurers_guide", "guide_wilds"),
                    "but content aimed at THIS placement must not resolve at the shared name - "
                            + "an alias goes one way");
        }

        @Test
        void aBareIdNothingDeclaresStillMatchesItself() {
            assertEquals(Set.of("some_vanilla_npc"), NpcIdentities.answerSetForPrimary("some_vanilla_npc"));
            assertTrue(NpcIdentities.primaryAnswersTo("some_vanilla_npc", "some_vanilla_npc"));
        }

        @Test
        void twoPlacementsSharingOnePrimaryContributeTheUnionOfTheirAliases() {
            loadPlacements(placement("hub_one", "guide", "north_guide"),
                    placement("hub_two", "guide", "south_guide"));
            Set<String> answers = NpcIdentities.answerSetForPrimary("guide");
            assertTrue(answers.containsAll(List.of("guide", "north_guide", "south_guide")),
                    "one step must be resolvable at either place the character stands");
        }

        @Test
        void anOverlaysAliasesJoinTheSameAnswerSetAsAPlacementsDo() {
            loadIdentities(NpcIdentityAsset.of("elder", "Kweebec_Elder", null, "village_elder",
                    new String[] {"elder"}));
            assertTrue(NpcIdentities.primaryAnswersTo("village_elder", "elder"));
        }

        @Test
        void repeatedAndBlankAliasEntriesAreDroppedRatherThanCounted() {
            loadPlacements(placement("hub", "guide", "shared", "  ", "shared", "SHARED"));
            assertEquals(List.of("shared"), NpcIdentities.aliasesOf("hub"));
        }
    }

    @Nested
    class TheReverseIndex {

        @Test
        void aPlacementIsFoundByItsPrimaryAndByEveryAliasItAnswersTo() {
            loadPlacements(placement("guide_wilds", "guide_wilds", "adventurers_guide"));
            assertEquals(List.of("guide_wilds"), NpcIdentities.placementsForNpcId("guide_wilds"));
            assertEquals(List.of("guide_wilds"), NpcIdentities.placementsForNpcId("adventurers_guide"));
        }

        @Test
        void anAliasedCharacterStandingTwiceIsFoundInBothPlaces() {
            loadPlacements(placement("guide_wilds", "guide_wilds", "adventurers_guide"),
                    placement("guide_sands", "guide_sands", "adventurers_guide"));
            assertEquals(List.of("guide_sands", "guide_wilds"),
                    NpcIdentities.placementsForNpcId("adventurers_guide"),
                    "a waypoint for the shared name must mark both (either one serves the quest), "
                            + "in a stable order rather than however the pool happened to hash");
        }

        @Test
        void theDeclaredVocabularyIsWhatAValidatorChecksAuthoredContentAgainst() {
            loadPlacements(placement("hub", "adventurers_guide", "town_crier"));
            loadIdentities(NpcIdentityAsset.of("elder", "Kweebec_Elder", null, "village_elder", null));
            assertTrue(NpcIdentities.allDeclaredNpcIds()
                    .containsAll(List.of("adventurers_guide", "town_crier", "village_elder")));
        }

        @Test
        void reloadingTheContentIsVisibleOnTheNextLookup() {
            loadPlacements(placement("hub", "first_name"));
            assertEquals("first_name", NpcIdentities.npcIdOfPlacement("hub"));

            loadPlacements(placement("hub", "second_name"));
            assertEquals("second_name", NpcIdentities.npcIdOfPlacement("hub"),
                    "a cached index that survived a reload would answer with yesterday's content");
        }
    }
}
