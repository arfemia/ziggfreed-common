package com.ziggfreed.common.objectives.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The two seams that let a consumer with its own persistence backend keep the library's default
 * stores as THE store instead of bringing a second one - which would be two versions of one player's
 * state rather than a composition.
 *
 * <p>The first is the dirty/flush FAN-OUT: both stores report every change and every transaction
 * boundary to whatever registered through {@link ProgressionDefaults#onProgressDirty} /
 * {@link ProgressionDefaults#onProgressFlush}, every listener is asked, and one that throws is
 * reported without costing the others their notification.
 *
 * <p>The second is where the COMPONENT comes from: a subject whose handle is the component itself,
 * and a subject whose handle merely answers for one through {@link Subject.HandleFacets}, both read
 * and write exactly as the library's own handle does.
 *
 * <p>The fan-out is only worth as much as its COVERAGE, so the last block drives real engines over
 * the real default stores and pins the writes that are easiest to forget: a pin and an unpin are
 * display preferences rather than progress, and a backend that never hears about them hands the
 * player back a tracker they already tidied. The one covered write this test cannot reach is a
 * dialogue memory, because {@code ZigProgressDialogueStore} needs a live component type and a world
 * before it will hand out its view; that one belongs to in-game smoke with the rest of this module's
 * engine-touching half.
 *
 * <p><b>These hooks reset the module's WHOLE static state</b>, not just the two listener lists:
 * {@link ProgressionDefaults#reset()} also drops the folded catalogues and clears the registered
 * flag. Nothing in this module's suite registers the defaults, so that costs nothing today - but a
 * later test here must register what it needs in its own setup rather than expecting registration
 * to survive from another class.
 */
class ProgressStoreContributionTest {

    private static final String QUEST_ID = "q_pinned";
    private static final String ACHIEVEMENT_ID = "a_pinned";

    @BeforeEach
    @AfterEach
    void clearListeners() {
        ProgressionDefaults.reset();
    }

    // ==================== the dirty / flush fan-out ====================

    @Test
    void bothStoresFanDirtyOutToEveryListener() {
        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressDirty(subject -> seen.add("first:" + subject.name()));
        ProgressionDefaults.onProgressDirty(subject -> seen.add("second:" + subject.name()));

        Subject subject = componentSubject(new ZigProgressComponent(), "Ari");
        ZigQuestStore.INSTANCE.markDirty(subject);
        ZigAchievementStore.INSTANCE.markDirty(subject);

        assertEquals(List.of("first:Ari", "second:Ari", "first:Ari", "second:Ari"), seen,
                "a contribution stacks: registering one listener never displaces another, and both"
                        + " stores report through the same fan-out");
    }

    @Test
    void bothStoresFanFlushOutToEveryListener() {
        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressFlush(subject -> seen.add("first:" + subject.name()));
        ProgressionDefaults.onProgressFlush(subject -> seen.add("second:" + subject.name()));

        Subject subject = componentSubject(new ZigProgressComponent(), "Ari");
        ZigQuestStore.INSTANCE.flush(subject);
        ZigAchievementStore.INSTANCE.flush(subject);

        assertEquals(List.of("first:Ari", "second:Ari", "first:Ari", "second:Ari"), seen);
    }

    @Test
    void dirtyAndFlushAreSeparateContributions() {
        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressDirty(subject -> seen.add("dirty"));
        ProgressionDefaults.onProgressFlush(subject -> seen.add("flush"));

        Subject subject = componentSubject(new ZigProgressComponent(), "Ari");
        ZigQuestStore.INSTANCE.markDirty(subject);

        assertEquals(List.of("dirty"), seen,
                "a backend that batches needs to hear 'this changed' and 'commit it now' apart");

        ZigQuestStore.INSTANCE.flush(subject);
        assertEquals(List.of("dirty", "flush"), seen);
    }

    @Test
    void aThrowingListenerDoesNotSilenceTheOthers() {
        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressDirty(subject -> seen.add("before"));
        ProgressionDefaults.onProgressDirty(subject -> {
            throw new IllegalStateException("this backend is down");
        });
        ProgressionDefaults.onProgressDirty(subject -> seen.add("after"));

        Subject subject = componentSubject(new ZigProgressComponent(), "Ari");
        ZigQuestStore.INSTANCE.markDirty(subject);

        assertEquals(List.of("before", "after"), seen,
                "one failing backend must not cost another its notification");
    }

    @Test
    void aThrowingFlushListenerDoesNotSilenceTheOthers() {
        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressFlush(subject -> {
            throw new IllegalStateException("this backend is down");
        });
        ProgressionDefaults.onProgressFlush(subject -> seen.add("after"));

        ZigAchievementStore.INSTANCE.flush(componentSubject(new ZigProgressComponent(), "Ari"));

        assertEquals(List.of("after"), seen);
    }

    @Test
    void aListenerThrowingAnErrorIsGuardedToo() {
        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressDirty(subject -> {
            throw new StackOverflowError("a backend recursing into itself");
        });
        ProgressionDefaults.onProgressDirty(subject -> seen.add("after"));

        ZigQuestStore.INSTANCE.markDirty(componentSubject(new ZigProgressComponent(), "Ari"));

        assertEquals(List.of("after"), seen,
                "the guard catches Throwable rather than Exception: an Error out of one backend"
                        + " must not tear down the quest transition that was reporting to it");
    }

    @Test
    void aNullListenerIsRefusedAtRegistration() {
        assertThrows(NullPointerException.class, () -> ProgressionDefaults.onProgressDirty(null),
                "refusing here names the offending consumer at its own setup; accepting one would"
                        + " throw out of a fan-out mid-transition, from inside the guard itself");
        assertThrows(NullPointerException.class, () -> ProgressionDefaults.onProgressFlush(null));
    }

    @Test
    void resetClearsBothContributions() {
        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressDirty(subject -> seen.add("dirty"));
        ProgressionDefaults.onProgressFlush(subject -> seen.add("flush"));

        ProgressionDefaults.reset();

        Subject subject = componentSubject(new ZigProgressComponent(), "Ari");
        ZigQuestStore.INSTANCE.markDirty(subject);
        ZigQuestStore.INSTANCE.flush(subject);

        assertTrue(seen.isEmpty(), "a reset that left listeners behind would leak one test's"
                + " backend into the next one's run");
    }

    @Test
    void aFanOutWithNoListenersStaysOpenToOneRegisteredLater() {
        // The no-listener path is what every default server runs, and a consumer registers during
        // its own setup, which can be after the first moment has already gone past.
        Subject subject = componentSubject(new ZigProgressComponent(), "Ari");
        ZigQuestStore.INSTANCE.markDirty(subject);
        ZigAchievementStore.INSTANCE.flush(subject);

        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressDirty(s -> seen.add("dirty"));
        ProgressionDefaults.onProgressFlush(s -> seen.add("flush"));

        ZigQuestStore.INSTANCE.markDirty(subject);
        ZigAchievementStore.INSTANCE.flush(subject);

        assertEquals(List.of("dirty", "flush"), seen,
                "an empty fan-out must not latch: a backend registering late still hears everything"
                        + " from the moment it registers");
    }

    // ==================== where the component comes from ====================

    @Test
    void aBareComponentHandleDrivesTheQuestStore() {
        ZigProgressComponent component = new ZigProgressComponent();
        Subject subject = componentSubject(component, "Ari");

        ZigQuestStore.INSTANCE.setStatus(subject, "quest_one", QuestStatus.ACTIVE);

        assertEquals(QuestStatus.ACTIVE, component.questStatus("quest_one"),
                "the write reached the component the subject handed over");
        assertEquals(QuestStatus.ACTIVE, ZigQuestStore.INSTANCE.status(subject, "quest_one"));
    }

    @Test
    void aBareComponentHandleDrivesTheAchievementStore() {
        ZigProgressComponent component = new ZigProgressComponent();
        Subject subject = componentSubject(component, "Ari");

        ZigAchievementStore.INSTANCE.putProgress(subject, "ach_one#0", 12L);
        ZigAchievementStore.INSTANCE.setStatus(subject, "ach_one", AchievementStatus.UNLOCKED);

        assertEquals(12L, component.achievementProgress("ach_one#0"));
        assertEquals(12L, ZigAchievementStore.INSTANCE.progress(subject, "ach_one#0"));
        assertEquals(AchievementStatus.UNLOCKED, ZigAchievementStore.INSTANCE.status(subject, "ach_one"));
    }

    @Test
    void aFacetHandleThatAnswersForTheComponentDrivesBothStores() {
        ZigProgressComponent component = new ZigProgressComponent();
        Subject subject = new Subject(UUID.randomUUID(), "Ari", new ConsumerHandle(component));

        assertSame(component, subject.handleAs(ZigProgressComponent.class),
                "a consumer's own handle answering for the component is the whole seam");

        ZigQuestStore.INSTANCE.setStatus(subject, "quest_one", QuestStatus.COMPLETED);
        ZigAchievementStore.INSTANCE.putProgress(subject, "ach_one#0", 5L);

        assertEquals(QuestStatus.COMPLETED, component.questStatus("quest_one"));
        assertEquals(5L, component.achievementProgress("ach_one#0"));
    }

    @Test
    void aSubjectWithNoComponentAnywhereReadsNeutralAndDropsWrites() {
        Subject subject = Subject.of(UUID.randomUUID(), "Ari");

        ZigQuestStore.INSTANCE.setStatus(subject, "quest_one", QuestStatus.ACTIVE);
        ZigAchievementStore.INSTANCE.putProgress(subject, "ach_one#0", 12L);

        assertEquals(QuestStatus.NOT_STARTED, ZigQuestStore.INSTANCE.status(subject, "quest_one"));
        assertEquals(0L, ZigAchievementStore.INSTANCE.progress(subject, "ach_one#0"));
    }

    // ==================== what the fan-out actually covers ====================

    @Test
    void pinningAndUnpinningAQuestBothReachTheFanOut() {
        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressDirty(subject -> seen.add("dirty"));

        ZigProgressComponent component = new ZigProgressComponent();
        Subject subject = componentSubject(component, "Ari");
        QuestEngine engine = questEngine();
        engine.setQuests(List.of(trackableQuest()));

        assertTrue(engine.track(subject, QUEST_ID));
        assertEquals(1, seen.size(), "a pin is saved state, so a batching backend has to hear it");

        assertTrue(engine.untrack(subject, QUEST_ID));
        assertEquals(2, seen.size(), "and so is taking one off, or the pin comes back on hydrate");

        assertTrue(component.trackedPins().isEmpty());
    }

    @Test
    void pruningDeadQuestPinsReachesTheFanOutOnceAndOnlyWhenSomethingWent() {
        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressDirty(subject -> seen.add("dirty"));

        ZigProgressComponent component = new ZigProgressComponent();
        Subject subject = componentSubject(component, "Ari");
        QuestEngine engine = questEngine();
        engine.setQuests(List.of(trackableQuest()));

        assertTrue(engine.track(subject, QUEST_ID));
        seen.clear();

        // The quest was never accepted, so the pin is dead the moment it was made.
        assertEquals(1, engine.pruneStaleTracked(subject));
        assertEquals(List.of("dirty"), seen,
                "a sweep that dropped pins reports once, not once per pin");

        seen.clear();
        assertEquals(0, engine.pruneStaleTracked(subject));
        assertTrue(seen.isEmpty(), "a sweep that dropped nothing changed nothing");
    }

    @Test
    void pinningAndUnpinningAnAchievementBothReachTheFanOut() {
        List<String> seen = new ArrayList<>();
        ProgressionDefaults.onProgressDirty(subject -> seen.add("dirty"));

        Subject subject = componentSubject(new ZigProgressComponent(), "Ari");
        AchievementEngine engine = AchievementEngine.builder()
                .store(ZigAchievementStore.INSTANCE)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setAchievements(List.of(Achievement.builder(ACHIEVEMENT_ID)
                .criterion(ObjectiveDef.builder("0", "BREAK_BLOCK").target("Stone").amount(1)
                        .build())
                .build()));

        assertTrue(engine.pin(subject, ACHIEVEMENT_ID));
        assertEquals(1, seen.size());

        assertTrue(engine.unpin(subject, ACHIEVEMENT_ID));
        assertEquals(2, seen.size(),
                "unpin was the asymmetric one: its twin reported the change and it did not");
    }

    @Nonnull
    private static QuestEngine questEngine() {
        return QuestEngine.builder()
                .store(ZigQuestStore.INSTANCE)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
    }

    @Nonnull
    private static Quest trackableQuest() {
        return Quest.builder(QUEST_ID)
                .objective(ObjectiveDef.builder("step", "BREAK_BLOCK").target("Stone").amount(1)
                        .build())
                .build();
    }

    @Nonnull
    private static Subject componentSubject(@Nonnull ZigProgressComponent component,
            @Nonnull String name) {
        return new Subject(UUID.randomUUID(), name, component);
    }

    /**
     * Stands in for a CONSUMER's own subject handle: something richer than the component that still
     * says it can answer for one. This is what keeps the default stores usable on a server whose
     * subject source belongs to somebody else.
     */
    private record ConsumerHandle(@Nonnull ZigProgressComponent component)
            implements Subject.HandleFacets {

        @Override
        @Nullable
        public Object facet(@Nonnull Class<?> type) {
            return type.isAssignableFrom(ZigProgressComponent.class) ? component : null;
        }
    }
}
