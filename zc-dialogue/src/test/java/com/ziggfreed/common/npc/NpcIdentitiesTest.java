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

    /** A placement standing role {@code Some_Role} and authoring {@code npcId} (null = the default). */
    private static NpcPlacementAsset placement(String id, String npcId, String... aliases) {
        return roled(id, "Some_Role", npcId, aliases);
    }

    /** As {@link #placement}, naming the role explicitly - the leaf the default now reads. */
    private static NpcPlacementAsset roled(String id, String role, String npcId, String... aliases) {
        return NpcPlacementAsset.of(id, null,
                NpcPlacementAsset.Identity.of(role, npcId,
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
        void aPlacementThatAuthorsNoIdIsItsRole() {
            loadPlacements(roled("wilds_camp", "Guide_Wilds", null));
            assertEquals("Guide_Wilds", NpcIdentities.npcIdOfPlacement("wilds_camp"),
                    "standing an NPC somewhere must be enough to make it nameable by content, and "
                            + "the name it is nameable BY is the character it stands: its role");
        }

        @Test
        void twoPlacementsOfOneRoleAreTwoStandingsOfOneCharacter() {
            loadPlacements(roled("wilds_camp", "Guide_Wilds", null),
                    roled("sands_camp", "Guide_Wilds", null));
            assertEquals("Guide_Wilds", NpcIdentities.npcIdOfPlacement("wilds_camp"));
            assertEquals("Guide_Wilds", NpcIdentities.npcIdOfPlacement("sands_camp"),
                    "a quest bound to the character must be creditable at either standing, with "
                            + "nothing authored on the second placement to say so");
        }

        @Test
        void anAuthoredIdOptsOutOfTheRoleDefault() {
            loadPlacements(roled("temple", "MMO_Hub", "Mmo_Hub_Temple"));
            assertEquals("Mmo_Hub_Temple", NpcIdentities.npcIdOfPlacement("temple"),
                    "an authored id is how one standing becomes a character of its own, which is "
                            + "what scopes a step to it");
            assertFalse(NpcIdentities.answersTo("temple", "MMO_Hub"),
                    "opting out means opting out: the role id is no longer answered to unless an "
                            + "alias says so");
        }

        @Test
        void aPlacementNothingLoadedNamesIsNobody() {
            assertNull(NpcIdentities.npcIdOfPlacement("never_authored"));
            assertNull(NpcIdentities.npcIdOfPlacement(null));
            assertNull(NpcIdentities.npcIdOfPlacement("  "));
        }

        @Test
        void aPlacementNamingNeitherAnIdNorARoleIsNobody() {
            loadPlacements(roled("broken", null, null));
            assertNull(NpcIdentities.npcIdOfPlacement("broken"),
                    "it stands nobody up, so inventing an id from its file name would mint a "
                            + "character that is never in the world for content to bind to");
        }

        @Test
        void aRoleWithNoOverlayIsItsOwnNameSpelledAsItIs() {
            assertEquals("Kweebec_Elder", NpcIdentities.npcIdOfRole("Kweebec_Elder"),
                    "the convention is the whole point: most NPCs need no file at all, and it must "
                            + "give the same answer the placement rung does for the same role");
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
            assertEquals("Kweebec_Elder", NpcIdentities.npcIdOfRole("Kweebec_Elder"));
        }
    }

    /**
     * Every question about WHO an NPC is ignores case, whatever the answer set was spelled in. It is
     * pinned on its own because the ids being compared are written on files that never see each
     * other - a placement, a quest, a conversation - so one capital letter apart is a step that
     * never completes and reports nothing.
     */
    @Nested
    class CaseInsensitivity {

        @Test
        void aRoleDefaultedIdIsAnsweredToInAnyCase() {
            loadPlacements(roled("wilds_camp", "Guide_Wilds", null));
            assertTrue(NpcIdentities.answersTo("wilds_camp", "guide_wilds"));
            assertTrue(NpcIdentities.answersTo("WILDS_CAMP", "GUIDE_WILDS"),
                    "the placement key is normalized too, not only the id being asked about");
            assertTrue(NpcIdentities.primaryAnswersTo("Guide_Wilds", "guide_wilds"));
        }

        @Test
        void anAliasIsMatchedInAnyCaseAndFoundInTheReverseIndex() {
            loadPlacements(roled("temple", "MMO_Hub", "Mmo_Hub_Temple", "mmo_hub"));
            assertTrue(NpcIdentities.primaryAnswersTo("MMO_HUB_TEMPLE", "MMO_Hub"));
            assertEquals(List.of("temple"), NpcIdentities.placementsForNpcId("MMO_HUB"));
        }

        @Test
        void aRoleDefaultKeepsItsAuthoredSpellingInTheAnswerSet() {
            loadPlacements(roled("wilds_camp", "Guide_Wilds", null));
            assertEquals(List.of("Guide_Wilds"), List.copyOf(NpcIdentities.answerSetOf("wilds_camp")),
                    "a display key is built from this string verbatim (npcs.<id>.name), so the "
                            + "authored spelling must survive even though matching ignores it");
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
        void bothStandingsOfARoleAreFoundByThatRolesId() {
            loadPlacements(roled("sands_camp", "Guide_Wilds", null),
                    roled("wilds_camp", "Guide_Wilds", null));
            assertEquals(List.of("sands_camp", "wilds_camp"),
                    NpcIdentities.placementsForNpcId("Guide_Wilds"),
                    "the default identity must reach the reverse index the same way an authored one "
                            + "does, or a waypoint would point at only one of the two");
        }

        @Test
        void anOptedOutStandingIsStillFoundByTheAliasItKeeps() {
            loadPlacements(roled("spawn", "MMO_Hub", null),
                    roled("temple", "MMO_Hub", "Mmo_Hub_Temple", "MMO_Hub"));
            assertEquals(List.of("spawn", "temple"), NpcIdentities.placementsForNpcId("MMO_Hub"),
                    "the temple guide is a character of its own, but it still ANSWERS to the shared "
                            + "id, so a quest bound there marks both");
            assertEquals(List.of("temple"), NpcIdentities.placementsForNpcId("Mmo_Hub_Temple"),
                    "and its own id reaches only it - which is the whole point of opting out");
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
