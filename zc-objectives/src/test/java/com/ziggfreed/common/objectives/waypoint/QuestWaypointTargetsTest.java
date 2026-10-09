package com.ziggfreed.common.objectives.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.indicator.QuestIndicators;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.asset.QuestIndicatorSpec;
import com.ziggfreed.common.quest.asset.QuestSituation;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.world.WorldSelector;
import com.ziggfreed.common.worldmap.GatewayAsset;
import com.ziggfreed.common.worldmap.WaypointTarget;

/**
 * Where a tracked quest points: its current step's character, met directly in the world they stand
 * in and through a gateway from any other, over a real engine with an in-memory store; and the map
 * marks at characters with a quest on offer.
 */
class QuestWaypointTargetsTest {

    private static final Predicate<String> PLACE_TARGETED = kind -> "TALK_TO_NPC".equalsIgnoreCase(kind);
    private static final WorldSelector TEMPLE = WorldSelector.of(null, new String[]{"ForgottenTemple"}, null);
    private static final WorldSelector OVERWORLD = WorldSelector.of(new String[]{"default"}, null, null);
    private static final String TEMPLE_WORLD = "instance-forgotten-temple-goblins";
    private static final GatewayAsset TEMPLE_GATEWAY = GatewayAsset.of("Forgotten_Temple_Portal_Enter", null, null,
            GatewayAsset.Into.of(TEMPLE_WORLD, "ForgottenTemple"), new String[]{"Forgotten_Temple_Portal_Enter"}, null);
    private static final String TEMPLE_KEY = QuestWaypointTargets.GATEWAY_PREFIX + "forgotten_temple_portal_enter";

    private QuestEngine engine;
    private Subject player;

    @BeforeEach
    void engine() {
        engine = QuestEngine.builder().nativeEvents(false).warn(message -> { })
                .possessionProbe((itemId, count) -> true)
                .maxActive(10)
                .build();
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    private static Quest meet(String id, String npc) {
        return Quest.builder(id).npcViewId(npc)
                .objective(ObjectiveDef.builder("meet", "TURN_IN").target("").amount(1).turnInLockId(npc).build())
                .build();
    }

    private void carry(boolean track, Quest... quests) {
        engine.setQuests(List.of(quests));
        for (Quest quest : quests) {
            assertTrue(engine.accept(player, quest, quest.npcViewId()));
            if (track) {
                assertTrue(engine.track(player, quest.id()));
            }
        }
    }

    private List<WaypointTarget> plan(String worldName, String gameplayConfig, Map<String, List<WorldSelector>> wheres) {
        return QuestWaypointTargets.plan(engine, player, worldName, gameplayConfig, PLACE_TARGETED,
                npc -> wheres.getOrDefault(npc, List.of()), List.of(TEMPLE_GATEWAY), quest -> null, "Quest_Pin.png");
    }

    private List<String> keys(String worldName, String gameplayConfig, Map<String, List<WorldSelector>> wheres) {
        return plan(worldName, gameplayConfig, wheres).stream().map(WaypointTarget::positionKey).toList();
    }

    @Test
    void aHandInLockedToACharacterPointsAtThem() {
        assertEquals("Old_Jack", QuestWaypointTargets.destinationOf(List.of(ObjectiveDef.builder("meet", "TURN_IN")
                .target("").amount(1).turnInLockId("Old_Jack").build()), PLACE_TARGETED));
    }

    @Test
    void aTalkStepPointsAtTheCharacterItNames() {
        assertEquals("Orrin", QuestWaypointTargets.destinationOf(List.of(ObjectiveDef.builder("talk", "TALK_TO_NPC")
                .target("Orrin").amount(1).build()), PLACE_TARGETED));
    }

    @Test
    void aStepNamingNoPlacePointsNowhere() {
        assertNull(QuestWaypointTargets.destinationOf(List.of(
                ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build(),
                ObjectiveDef.builder("hand", "TURN_IN").target("Copper_Ore").amount(3).build()), PLACE_TARGETED));
    }

    @Test
    void aCharacterStandingInThisWorldIsPointedAtDirectlyWithThePointersIcon() {
        carry(true, meet("meet_jack", "Old_Jack"));
        List<WaypointTarget> targets = plan(TEMPLE_WORLD, "ForgottenTemple", Map.of("Old_Jack", List.of(TEMPLE)));
        assertEquals(List.of("Old_Jack"), targets.stream().map(WaypointTarget::positionKey).toList());
        assertEquals("Quest_Pin.png", targets.get(0).icon());
    }

    @Test
    void aCharacterInAnotherWorldIsReachedThroughTheGatewayHere() {
        carry(true, meet("meet_jack", "Old_Jack"));
        assertEquals(List.of(TEMPLE_KEY), keys("default", "Default", Map.of("Old_Jack", List.of(TEMPLE))));
    }

    @Test
    void aCharacterAlsoStandingHereIsNeverRoutedThroughAGateway() {
        carry(true, meet("meet_guide", "Guide"));
        assertEquals(List.of("Guide"), keys("default", "Default", Map.of("Guide", List.of(TEMPLE, OVERWORLD))));
    }

    @Test
    void twoCharactersInsideShareOneGatewayMarker() {
        carry(true, meet("meet_jack", "Old_Jack"), meet("meet_orrin", "Orrin"));
        assertEquals(List.of(TEMPLE_KEY),
                keys("default", "Default", Map.of("Old_Jack", List.of(TEMPLE), "Orrin", List.of(TEMPLE))));
    }

    @Test
    void aCharacterWithNoWayFromHereIsNotPointedAt() {
        carry(true, meet("meet_arena", "Arena_Master"));
        assertTrue(keys("default", "Default",
                Map.of("Arena_Master", List.of(WorldSelector.of(null, new String[]{"Arena"}, null)))).isEmpty());
    }

    @Test
    void aCharacterNothingPlacesIsStillNamedForTheResolver() {
        carry(true, meet("meet_wanderer", "Wanderer"));
        assertEquals(List.of("Wanderer"), keys("default", "Default", Map.of()));
    }

    @Test
    void anUntrackedQuestPointsNowhere() {
        carry(false, meet("meet_jack", "Old_Jack"));
        assertTrue(keys("default", "Default", Map.of("Old_Jack", List.of(TEMPLE))).isEmpty());
    }

    @Test
    void aCharacterWithAQuestOnOfferIsMarkedWithTheSituationsIcon() {
        Quest offered = Quest.builder("q_offer").npcViewId("Guide")
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build())
                .build();
        QuestIndicators.MapMark mark = new QuestIndicators.MapMark("Guide", new QuestIndicators.Reading(
                QuestSituation.AVAILABLE, offered,
                new QuestIndicatorSpec.Resolved(true, "Quest_Available", true, true, "Coordinate.png")));

        List<WaypointTarget> targets = QuestWaypointTargets.available(List.of(mark), quest -> null);

        assertEquals(1, targets.size());
        assertEquals("Guide", targets.get(0).id());
        assertEquals("Guide", targets.get(0).positionKey());
        assertEquals("Coordinate.png", targets.get(0).icon());
    }

    @Test
    void whileAConsumerDrawsItsOwnOnlyThePointersThroughAGatewayStay() {
        List<WaypointTarget> kept = QuestWaypointTargets.whileConsumerDraws(List.of(
                WaypointTarget.of("Old_Jack", null), WaypointTarget.of(TEMPLE_KEY, null)));
        assertEquals(List.of(TEMPLE_KEY), kept.stream().map(WaypointTarget::positionKey).toList());
    }
}
