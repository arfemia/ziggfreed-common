package com.ziggfreed.common.objectives.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.asset.QuestIndicatorSpec;
import com.ziggfreed.common.quest.asset.QuestSituation;
import com.ziggfreed.common.subject.Subject;

/**
 * The one availability answer: which situations a character is in for a player, in what order,
 * and the knob each reads through the three scopes. Driven over a real engine with an in-memory
 * store and a test-owned offer table; the global scope is seeded into the config table directly
 * and cleared afterwards.
 */
class QuestIndicatorsTest {

    private static final String GUIDE = "guide";
    private static final Set<String> AT_GUIDE = Set.of(GUIDE);

    private QuestEngine engine;
    private Subject player;

    @BeforeEach
    void engine() {
        engine = QuestEngine.builder().nativeEvents(false).warn(message -> { })
                .possessionProbe((itemId, count) -> true)
                .maxActive(10)
                .build();
        player = Subject.of(UUID.randomUUID(), "tester");
        NpcOfferProviders.register("test", "test", (subject, answersTo) -> {
            List<NpcOffer> out = new ArrayList<>();
            for (Quest quest : engine.quests()) {
                if (quest.npcViewId() != null && containsIgnoreCase(answersTo, quest.npcViewId())
                        && engine.isOfferable(subject, quest)) {
                    out.add(NpcOffer.available(quest.id(), null));
                }
            }
            return out;
        });
    }

    @AfterEach
    void clear() {
        NpcOfferProviders.clear();
        QuestIndicatorConfig.getInstance().mergePackLayer(Map.of());
    }

    private static boolean containsIgnoreCase(Collection<String> ids, String wanted) {
        for (String id : ids) {
            if (id.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static Quest.Builder gather(String id) {
        return Quest.builder(id)
                .npcViewId(GUIDE)
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build());
    }

    private static Quest.Builder reportBack(String id) {
        return Quest.builder(id)
                .npcViewId(GUIDE)
                .objective(ObjectiveDef.builder("tell", "TURN_IN").target("").amount(1).turnInLockId(GUIDE).build());
    }

    private static void global(QuestIndicatorSpec spec) {
        QuestIndicatorConfig.getInstance().mergePackLayer(
                Map.of(QuestIndicatorAsset.DEFAULT_ID, QuestIndicatorAsset.of(QuestIndicatorAsset.DEFAULT_ID, spec)));
    }

    // ==================== situations and precedence ====================

    @Test
    void theSectionsThatAnnounceMapToSituationsAndTheRestToNothing() {
        assertEquals(QuestSituation.COLLECT, QuestIndicators.situationOf(Section.READY));
        assertEquals(QuestSituation.TURN_IN, QuestIndicators.situationOf(Section.TURN_IN));
        assertEquals(QuestSituation.AVAILABLE, QuestIndicators.situationOf(Section.AVAILABLE));
        assertEquals(QuestSituation.IN_PROGRESS, QuestIndicators.situationOf(Section.ACTIVE));
        assertNull(QuestIndicators.situationOf(Section.PARKED));
        assertNull(QuestIndicators.situationOf(Section.COOLDOWN));
        assertNull(QuestIndicators.situationOf(Section.LOCKED));
        assertNull(QuestIndicators.situationOf(Section.DONE));
    }

    @Test
    void collectBeatsTurnInBeatsAvailableBeatsInProgressAtOneCharacter() {
        Quest offered = gather("q_offered").build();
        Quest carried = gather("q_carried").build();
        Quest errand = reportBack("q_errand").build();
        Quest finished = gather("q_finished").build();
        engine.setQuests(List.of(offered, carried, errand, finished));
        assertTrue(engine.accept(player, carried, GUIDE));
        assertTrue(engine.accept(player, errand, GUIDE));
        assertTrue(engine.accept(player, finished, GUIDE));
        engine.markUnclaimed(player, finished);

        List<QuestIndicators.Reading> readings = QuestIndicators.situationsAt(engine, player, AT_GUIDE);
        assertEquals(List.of(QuestSituation.COLLECT, QuestSituation.TURN_IN, QuestSituation.AVAILABLE,
                        QuestSituation.IN_PROGRESS),
                readings.stream().map(QuestIndicators.Reading::situation).toList());
        assertEquals("q_finished", readings.get(0).quest().id());

        QuestIndicators.Reading overhead = QuestIndicators.overheadAt(engine, player, AT_GUIDE);
        assertNotNull(overhead);
        assertEquals(QuestSituation.COLLECT, overhead.situation());
        assertEquals(QuestSituation.COLLECT.defaultState(), overhead.knob().state());
    }

    @Test
    void aSituationWhoseOverheadIsOffYieldsToTheNextOne() {
        Quest offered = gather("q_offered").build();
        Quest finished = gather("q_finished")
                .indicator(QuestIndicatorSpec.of(null,
                        QuestIndicatorSpec.Situation.of(null, null, QuestIndicatorSpec.Overhead.of(false), null),
                        null, null, null))
                .build();
        engine.setQuests(List.of(offered, finished));
        assertTrue(engine.accept(player, finished, GUIDE));
        engine.markUnclaimed(player, finished);

        QuestIndicators.Reading overhead = QuestIndicators.overheadAt(engine, player, AT_GUIDE);
        assertNotNull(overhead);
        assertEquals(QuestSituation.AVAILABLE, overhead.situation(), "collect is switched off for that quest");
        assertEquals("q_offered", overhead.quest().id());
    }

    @Test
    void nobodyInFrontOfThePlayerIsNoSituationAtAll() {
        engine.setQuests(List.of(gather("q").build()));
        assertTrue(QuestIndicators.situationsAt(engine, player, Set.of()).isEmpty());
        assertNull(QuestIndicators.overheadAt(engine, player, Set.of()));
    }

    // ==================== the three scopes ====================

    @Test
    void theStepsWordNarrowsTheQuestsWhichNarrowsTheGlobal() {
        global(QuestIndicatorSpec.of(null, null,
                QuestIndicatorSpec.Situation.of(null, "Global_Hand_In", null,
                        QuestIndicatorSpec.MapMark.of(true, "Coordinate.png")),
                null, null));
        Quest quest = reportBack("q_errand")
                .indicator(QuestIndicatorSpec.of(null, null,
                        QuestIndicatorSpec.Situation.of(null, "Quest_Hand_In", null, null), null, null))
                .stepIndicator("tell", QuestIndicatorSpec.of(null, null,
                        QuestIndicatorSpec.Situation.of(null, null, QuestIndicatorSpec.Overhead.of(false), null),
                        null, null))
                .build();

        QuestIndicatorSpec.Resolved global = QuestIndicators.knobFor(QuestSituation.TURN_IN,
                Quest.builder("bare").build(), null);
        assertEquals("Global_Hand_In", global.state(), "a quest saying nothing reads the global word");
        assertTrue(global.showsMap());

        QuestIndicatorSpec.Resolved questLevel = QuestIndicators.knobFor(QuestSituation.TURN_IN, quest, null);
        assertEquals("Quest_Hand_In", questLevel.state(), "the quest's state wins over the global one");
        assertTrue(questLevel.showsOverhead(), "the step's leaf is not read at quest scope");
        assertTrue(questLevel.showsMap(), "the global map leaf survives a quest that never mentioned Map");

        QuestIndicatorSpec.Resolved stepLevel = QuestIndicators.knobFor(QuestSituation.TURN_IN, quest, "tell");
        assertEquals("Quest_Hand_In", stepLevel.state(), "the quest's state survives a step that only touched Overhead");
        assertFalse(stepLevel.showsOverhead(), "the step's Overhead.Enabled wins");
        assertTrue(stepLevel.showsMap());
        assertEquals("Coordinate.png", stepLevel.mapIcon());
    }

    @Test
    void theStepThatRaisesATurnInIsTheOneWhoseBlockIsRead() {
        Quest errand = reportBack("q_errand")
                .stepIndicator("tell", QuestIndicatorSpec.of(null, null,
                        QuestIndicatorSpec.Situation.of(null, "Step_Says_So", null, null), null, null))
                .build();
        engine.setQuests(List.of(errand));
        assertTrue(engine.accept(player, errand, GUIDE));

        QuestIndicators.Reading overhead = QuestIndicators.overheadAt(engine, player, AT_GUIDE);
        assertNotNull(overhead);
        assertEquals(QuestSituation.TURN_IN, overhead.situation());
        assertEquals("Step_Says_So", overhead.knob().state());
    }

    @Test
    void theStepThePlayerIsOnIsTheOneWhoseBlockNarrowsInProgress() {
        Quest carried = gather("q_carried")
                .stepIndicator("mine", QuestIndicatorSpec.of(null, null, null, null,
                        QuestIndicatorSpec.Situation.of(null, "Digging", null, null)))
                .build();
        engine.setQuests(List.of(carried));
        assertTrue(engine.accept(player, carried, GUIDE));

        QuestIndicators.Reading overhead = QuestIndicators.overheadAt(engine, player, AT_GUIDE);
        assertNotNull(overhead);
        assertEquals(QuestSituation.IN_PROGRESS, overhead.situation());
        assertEquals("Digging", overhead.knob().state());
    }

    // ==================== the map half ====================

    @Test
    void theMapMarksEveryGiverWithAMapEnabledSituationOncePerCharacter() {
        global(QuestIndicatorSpec.of(null, null, null,
                QuestIndicatorSpec.Situation.of(null, null, null, QuestIndicatorSpec.MapMark.of(true, "Coordinate.png")),
                null));
        Quest offeredHere = gather("q_a").build();
        Quest offeredHereToo = gather("q_b").build();
        Quest offeredThere = Quest.builder("q_c").npcViewId("Smith")
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Iron_Ore").build()).build();
        Quest carried = gather("q_d").build();
        engine.setQuests(List.of(offeredHere, offeredHereToo, offeredThere, carried));
        assertTrue(engine.accept(player, carried, GUIDE));

        List<QuestIndicators.MapMark> marks = QuestIndicators.mapMarksFor(engine, player);
        assertEquals(2, marks.size(), "one mark per character, whatever they offer");
        assertEquals(Set.of(GUIDE, "Smith"), Set.copyOf(marks.stream().map(QuestIndicators.MapMark::npcId).toList()),
                "one mark per character, in no particular order");
        for (QuestIndicators.MapMark mark : marks) {
            assertEquals(QuestSituation.AVAILABLE, mark.reading().situation());
            assertEquals("Coordinate.png", mark.reading().knob().mapIcon());
        }
    }

    @Test
    void withNoMapLeafOnNothingIsMarked() {
        engine.setQuests(List.of(gather("q_a").build()));
        assertTrue(QuestIndicators.mapMarksFor(engine, player).isEmpty(),
                "the library's own default marks nothing; the shipped Default.json is what turns Available on");
    }
}
