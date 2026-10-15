package com.ziggfreed.common.objectives.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.Quest.Repeat;
import com.ziggfreed.common.quest.Quest.Repeat.CooldownFrom;
import com.ziggfreed.common.quest.Quest.Repeat.PerRun;

/** The order a row reads its kind in: board, then repeat, then main line, with a yearly main quest still main. */
class QuestKindTest {

    private static Quest quest(boolean mainLine, Repeat repeat) {
        return Quest.builder("q").mainLine(mainLine).repeat(repeat).build();
    }

    private static Repeat perRunOnly() {
        return new Repeat(0L, CooldownFrom.CLAIM, null, 0, new PerRun("yearly_event", 1));
    }

    @Test
    void yearlyMainQuestReadsMain() {
        assertEquals(QuestKind.MAIN, QuestKind.of(quest(true, perRunOnly()), false));
    }

    @Test
    void dailyMainQuestStaysRepeatable() {
        assertEquals(QuestKind.REPEATABLE,
                QuestKind.of(quest(true, new Repeat(0L, CooldownFrom.CLAIM, Repeat.Reset.daily(), 0)), false));
    }

    @Test
    void perRunWithAPeriodicResetStaysRepeatable() {
        Repeat both = new Repeat(0L, CooldownFrom.CLAIM, Repeat.Reset.daily(), 0, new PerRun("yearly_event", 1));
        assertEquals(QuestKind.REPEATABLE, QuestKind.of(quest(true, both), false));
    }

    @Test
    void yearlySideQuestStaysRepeatable() {
        assertEquals(QuestKind.REPEATABLE, QuestKind.of(quest(false, perRunOnly()), false));
    }

    @Test
    void plainQuestsAndBoards() {
        assertEquals(QuestKind.MAIN, QuestKind.of(quest(true, null), false));
        assertEquals(QuestKind.SIDE, QuestKind.of(quest(false, null), false));
        assertEquals(QuestKind.BOUNTY, QuestKind.of(quest(true, perRunOnly()), true));
    }
}
