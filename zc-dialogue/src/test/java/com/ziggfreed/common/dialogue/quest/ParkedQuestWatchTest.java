package com.ziggfreed.common.dialogue.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.dialogue.state.DialogueFlagStore;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.quest.QuestStateReader;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The one signal a conversation routes on: a quest that PARKED across an option's actions.
 *
 * <p>What is worth pinning is every way the watch stays quiet, because each is a screen that would
 * otherwise be taken over for nothing: a quest that was already parked before the click, a quest
 * that paid out on the spot and never parked, a quest that is still being worked on, and a player
 * nothing can be read for. The one positive case is the quest that moved into waiting.
 */
class ParkedQuestWatchTest {

    private static final Subject PLAYER = Subject.of(UUID.randomUUID(), "tester");

    /** A quest runtime whose state the test moves by hand between the two reads. */
    private static final class ScriptedQuests implements DialogueQuests {

        final Map<String, QuestStatus> status = new LinkedHashMap<>();
        boolean unreadable;

        private final QuestStateReader reader = new QuestStateReader() {

            @Nonnull
            @Override
            public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
                return status.getOrDefault(questId, QuestStatus.NOT_STARTED);
            }

            @Nullable
            @Override
            public ObjectiveProgressState objectiveProgress(@Nonnull Subject subject,
                    @Nonnull String questId, @Nonnull String objectiveId) {
                return null;
            }

            @Nonnull
            @Override
            public List<String> activeAndUnclaimedIds(@Nonnull Subject subject) {
                List<String> out = new ArrayList<>();
                status.forEach((id, s) -> {
                    if (s == QuestStatus.ACTIVE || s == QuestStatus.COMPLETED_UNCLAIMED) {
                        out.add(id);
                    }
                });
                return out;
            }

            @Override
            public boolean canDeliverTurnInAt(@Nonnull Subject subject, @Nonnull String questId,
                    @Nullable String atId) {
                return false;
            }

            @Override
            public boolean hasDeliverableTurnInAt(@Nonnull Subject subject, @Nullable String atId) {
                return false;
            }
        };

        @Nonnull
        @Override
        public QuestStateReader reader() {
            return reader;
        }

        @Nonnull
        @Override
        public Subject subject(@Nonnull DialogueContext ctx) {
            if (unreadable) {
                throw new IllegalStateException("no player behind this conversation");
            }
            return PLAYER;
        }
    }

    /** A context with nothing behind it: the watch never reads through it, only hands it on. */
    private static final DialogueContext CONTEXT = new DialogueContext() {

        @Nonnull
        @Override
        public Store<EntityStore> store() {
            throw new UnsupportedOperationException();
        }

        @Nonnull
        @Override
        public Ref<EntityStore> ref() {
            throw new UnsupportedOperationException();
        }

        @Nullable
        @Override
        public PlayerRef playerRef() {
            return null;
        }

        @Nonnull
        @Override
        public Player player() {
            throw new UnsupportedOperationException();
        }

        @Nullable
        @Override
        public String contextId() {
            return "guide";
        }

        @Nonnull
        @Override
        public DialogueFlagStore flags() {
            throw new UnsupportedOperationException();
        }

        @Nullable
        @Override
        public <T> T payload(@Nonnull Class<T> type) {
            return null;
        }
    };

    @Test
    void aQuestThatMovedIntoWaitingIsNoticed() {
        ScriptedQuests quests = new ScriptedQuests();
        quests.status.put("errand", QuestStatus.ACTIVE);
        ParkedQuestWatch watch = ParkedQuestWatch.begin(quests, CONTEXT);

        quests.status.put("errand", QuestStatus.COMPLETED_UNCLAIMED);

        assertEquals("errand", watch.newlyParked(quests, CONTEXT));
    }

    @Test
    void aQuestTakenAndFinishedInsideOneOptionIsNoticedToo() {
        ScriptedQuests quests = new ScriptedQuests();
        ParkedQuestWatch watch = ParkedQuestWatch.begin(quests, CONTEXT);

        quests.status.put("errand", QuestStatus.COMPLETED_UNCLAIMED);

        assertEquals("errand", watch.newlyParked(quests, CONTEXT),
                "not started before, waiting after: it parked during the actions");
    }

    @Test
    void aQuestAlreadyWaitingBeforeTheClickIsNotNoticedAgain() {
        ScriptedQuests quests = new ScriptedQuests();
        quests.status.put("errand", QuestStatus.COMPLETED_UNCLAIMED);
        ParkedQuestWatch watch = ParkedQuestWatch.begin(quests, CONTEXT);

        assertNull(watch.newlyParked(quests, CONTEXT), "the route must never fire twice");
    }

    @Test
    void aQuestThatPaidOutOnTheSpotNeverParksAndIsNotNoticed() {
        ScriptedQuests quests = new ScriptedQuests();
        quests.status.put("errand", QuestStatus.ACTIVE);
        ParkedQuestWatch watch = ParkedQuestWatch.begin(quests, CONTEXT);

        quests.status.put("errand", QuestStatus.COMPLETED);

        assertNull(watch.newlyParked(quests, CONTEXT), "an autoclaim quest has nothing to collect");
    }

    @Test
    void aQuestStillBeingWorkedOnIsNotNoticed() {
        ScriptedQuests quests = new ScriptedQuests();
        quests.status.put("errand", QuestStatus.ACTIVE);
        ParkedQuestWatch watch = ParkedQuestWatch.begin(quests, CONTEXT);

        assertNull(watch.newlyParked(quests, CONTEXT));
    }

    @Test
    void theFirstOfSeveralNewlyParkedQuestsWins() {
        ScriptedQuests quests = new ScriptedQuests();
        quests.status.put("first", QuestStatus.ACTIVE);
        quests.status.put("second", QuestStatus.ACTIVE);
        ParkedQuestWatch watch = ParkedQuestWatch.begin(quests, CONTEXT);

        quests.status.put("first", QuestStatus.COMPLETED_UNCLAIMED);
        quests.status.put("second", QuestStatus.COMPLETED_UNCLAIMED);

        assertEquals("first", watch.newlyParked(quests, CONTEXT));
    }

    @Test
    void aPlayerNothingCanBeReadForIsWatchedQuietly() {
        ScriptedQuests quests = new ScriptedQuests();
        quests.unreadable = true;
        ParkedQuestWatch watch = ParkedQuestWatch.begin(quests, CONTEXT);

        quests.unreadable = false;
        quests.status.put("errand", QuestStatus.COMPLETED_UNCLAIMED);

        assertNull(watch.newlyParked(quests, CONTEXT),
                "no snapshot was possible, so nothing can be said to have changed");
    }
}
