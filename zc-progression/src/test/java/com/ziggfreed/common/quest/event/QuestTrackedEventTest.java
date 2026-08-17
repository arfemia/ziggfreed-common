package com.ziggfreed.common.quest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The pin event fires on a REAL change and on nothing else. A tracker repaints off this event, so
 * a pin write path that fired nothing would leave it one pin behind, and one that fired on a
 * re-stamp would repaint for no reason on every open of a page that pins.
 *
 * <p>Observed through the {@link QuestEvents.Publisher} seam: there is no engine bus in a unit
 * JVM, and this is exactly the case that seam exists for.
 */
class QuestTrackedEventTest {

    private final List<QuestTrackedEvent> fired = new ArrayList<>();
    private QuestEngine engine;
    private Subject player;
    private Quest first;
    private Quest second;

    @BeforeEach
    void setUp() {
        QuestEvents.publishTo(new QuestEvents.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                if (type == QuestTrackedEvent.class) {
                    fired.add((QuestTrackedEvent) build.get());
                }
            }
        });
        player = Subject.of(UUID.randomUUID(), "tester");
        first = quest("q_first").tag("demo").build();
        second = quest("q_second").build();
        engine = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(true)
                .maxTracked(1)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(first, second));
    }

    @AfterEach
    void restoreTheBus() {
        QuestEvents.publishTo(null);
    }

    @Nonnull
    private static Quest.Builder quest(@Nonnull String id) {
        return Quest.builder(id).objective(ObjectiveDef.builder("x", "BREAK_BLOCK")
                .target("Oak_Log").matchMode(MatchMode.EXACT).amount(1).build());
    }

    @Test
    void aFreshPinFiresOnceWithTheQuestsOwnTags() {
        engine.accept(player, first);
        assertTrue(engine.track(player, first.id()));

        assertEquals(1, fired.size());
        assertEquals(first.id(), fired.get(0).questId());
        assertEquals(player.id(), fired.get(0).playerId());
        assertTrue(fired.get(0).tracked());
        assertEquals(List.of("demo"), fired.get(0).tags());
    }

    @Test
    void reStampingALivePinFiresNothing() {
        engine.accept(player, first);
        engine.track(player, first.id());
        fired.clear();

        assertTrue(engine.track(player, first.id()), "re-pinning is still a success");
        assertTrue(fired.isEmpty(), "nothing the player watches changed, so nothing is announced");
    }

    @Test
    void aPinRefusedAtTheCapFiresNothing() {
        engine.accept(player, first);
        engine.accept(player, second);
        engine.track(player, first.id());
        fired.clear();

        assertFalse(engine.track(player, second.id()), "the cap is one");
        assertTrue(fired.isEmpty());
    }

    @Test
    void anUnknownQuestFiresNothing() {
        assertFalse(engine.track(player, "q_nobody_authored"));
        assertTrue(fired.isEmpty());
    }

    @Test
    void unpinningALivePinFiresOnceAsUntracked() {
        engine.accept(player, first);
        engine.track(player, first.id());
        fired.clear();

        assertTrue(engine.untrack(player, first.id()));
        assertEquals(1, fired.size());
        assertFalse(fired.get(0).tracked());
        assertEquals(first.id(), fired.get(0).questId());
    }

    @Test
    void unpinningWhatWasNeverPinnedFiresNothing() {
        assertFalse(engine.untrack(player, first.id()));
        assertTrue(fired.isEmpty());
    }

    @Test
    void aSweepFiresOncePerPinItDroppedAndNothingWhenItDroppedNone() {
        engine.accept(player, first);
        engine.track(player, first.id());
        fired.clear();

        assertEquals(0, engine.pruneStaleTracked(player), "the quest is still carried");
        assertTrue(fired.isEmpty(), "a sweep that dropped nothing says nothing");

        // Finishing the quest leaves its pin behind on a quest no longer carried; the next sweep
        // drops that stale pin and says so, once, naming it.
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        assertEquals(QuestStatus.COMPLETED, engine.status(player, first));
        fired.clear();
        int dropped = engine.pruneStaleTracked(player);
        assertEquals(1, dropped, "the one stale pin");
        assertEquals(1, fired.size(), "one announcement per dropped pin");
        assertFalse(fired.get(0).tracked());
        assertEquals(first.id(), fired.get(0).questId());
    }

    @Test
    void withNativeEventsOffNothingFires() {
        QuestEngine quiet = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        quiet.setQuests(List.of(first));
        quiet.accept(player, first);
        quiet.track(player, first.id());
        quiet.untrack(player, first.id());
        assertTrue(fired.isEmpty());
    }
}
