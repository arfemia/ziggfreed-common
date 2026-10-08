package com.ziggfreed.common.objectives.marker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.asset.QuestSituation;
import com.ziggfreed.common.subject.Subject;

/** What a character floats for a player: the winning situation's state, or nothing at all. */
class QuestOverheadsTest {

    private QuestEngine engine;
    private Subject player;

    @BeforeEach
    void engine() {
        engine = QuestEngine.builder().nativeEvents(false).warn(message -> { })
                .possessionProbe((itemId, count) -> true).maxActive(10).build();
        player = Subject.of(UUID.randomUUID(), "tester");
        NpcOfferProviders.register("test", "test", (subject, answersTo) -> {
            List<NpcOffer> out = new ArrayList<>();
            for (Quest quest : engine.quests()) {
                if (answersTo.contains(quest.npcViewId()) && engine.isOfferable(subject, quest)) {
                    out.add(NpcOffer.available(quest.id(), null));
                }
            }
            return out;
        });
    }

    @AfterEach
    void clear() {
        NpcOfferProviders.clear();
    }

    @Test
    void theOverheadStateIsTheWinningReadingsStateOrNothing() {
        engine.setQuests(List.of(Quest.builder("q_offer").npcViewId("guide")
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build())
                .build()));

        assertEquals(QuestSituation.AVAILABLE.defaultState(), QuestOverheads.stateFor(engine, player, Set.of("guide")));
        assertNull(QuestOverheads.stateFor(engine, player, Set.of("nobody")));
    }
}
