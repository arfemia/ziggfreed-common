package com.ziggfreed.common.objectives.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The default quest store is a component lookup and a call, so what is pinned here is the one
 * promise the adapter makes on top of the component: a quest id reaches the record under ANY casing,
 * because the engine reads and writes through this store and never through the component directly.
 * The subject carries the component as its own handle, the direct form every default store asks
 * for first.
 */
class ZigQuestStoreTest {

    @Test
    void theStoreAnswersAQuestIdUnderAnyCasingAndReSpellsItOnTheNextWrite() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setQuestStatus("My_Quest", QuestStatus.ACTIVE);
        component.putQuestPayload("My_Quest", "step:1/3");
        Subject subject = new Subject(UUID.randomUUID(), "tester", component);
        ZigQuestStore store = ZigQuestStore.INSTANCE;

        assertEquals(QuestStatus.ACTIVE, store.status(subject, "my_quest"),
                "a status saved under the authored casing answers the catalogue's spelling");
        assertEquals("step:1/3", store.progressPayload(subject, "MY_QUEST"));
        assertTrue(store.knownQuestIds(subject).contains("My_Quest"));

        store.setStatus(subject, "my_quest", QuestStatus.COMPLETED);
        store.putProgressPayload(subject, "my_quest", "step:3/3");

        assertEquals(Set.of("my_quest"), store.knownQuestIds(subject),
                "the write re-spelled the entry and left nothing under the saved casing");
        assertEquals(QuestStatus.COMPLETED, store.status(subject, "My_Quest"));

        store.clearQuest(subject, "MY_QUEST");
        assertEquals(QuestStatus.NOT_STARTED, store.status(subject, "my_quest"),
                "a clear under any casing empties the quest");
    }
}
