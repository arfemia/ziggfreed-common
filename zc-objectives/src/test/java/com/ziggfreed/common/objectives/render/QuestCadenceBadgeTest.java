package com.ziggfreed.common.objectives.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.quest.Quest;

/** The cadence word on a quest row: a once-a-run quest reads Seasonal, and a one-shot has no badge. */
class QuestCadenceBadgeTest {

    @Test
    void aOnceARunQuestReadsSeasonal() {
        Message label = QuestCadenceBadge.label(new Quest.Repeat(0L, Quest.Repeat.CooldownFrom.CLAIM, null, 0,
                new Quest.Repeat.PerRun("Spring_Fair", 1)));
        assertNotNull(label);
        assertEquals("ziggfreedcommon.progression.quest.cadence.seasonal", label.getMessageId());
        assertNull(QuestCadenceBadge.label(null), "a one-shot has no badge");
    }
}
