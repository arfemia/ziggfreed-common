package com.ziggfreed.common.objectives.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The path a collected quest's rewards actually walk, as far as a JVM with no server can walk it: a
 * real quest engine, the real per-reward payout pass, and a handler that finds its player exactly
 * the way every ready-made kind in this library does.
 *
 * <p>What it pins is the SEAM. A handler resolves its player with {@code subject.handleAs(...)}, so
 * a consumer attaching a richer handle than that one type gets nothing paid out - while the collect
 * itself still returns true and a surface still says "rewards collected". That combination is the
 * failure worth a test: it is invisible from every side except the player's inventory.
 *
 * <p>The engine {@code Player} cannot be constructed here, so an author-owned stand-in plays its
 * part. The resolution under test is the same either way, and it is the half that was wrong.
 */
class DefaultPartsRewardGrantTest {

    private static final String KIND = "test_payout";
    private static final String QUEST_ID = "q_paid";
    private static final String STEP_KIND = "BREAK_BLOCK";
    private static final String STEP_TARGET = "Oak_Log";

    /** Stands in for the engine {@code Player} a shipped reward kind resolves. */
    private record Avatar(@Nonnull String who) {
    }

    /** The shape of the runtime's real handle: rich, and able to stand in for what it carries. */
    private record RichHandle(@Nonnull Avatar avatar) implements Subject.HandleFacets {

        @Override
        @Nullable
        public Object facet(@Nonnull Class<?> type) {
            return type.isAssignableFrom(Avatar.class) ? avatar : null;
        }
    }

    /** A handle just as rich, but answering only for itself - the shape that pays out nothing. */
    private record OpaqueHandle(@Nonnull Avatar avatar) {
    }

    private final List<String> delivered = new ArrayList<>();

    private Quest quest;
    private QuestEngine engine;

    @BeforeEach
    void setUp() {
        delivered.clear();
        RewardKindRegistry kinds = new RewardKindRegistry("test-reward-kind");
        kinds.register(KIND, "test", (spec, subject) -> {
            Avatar avatar = subject.handleAs(Avatar.class);
            if (avatar == null) {
                // Word for word what a shipped kind does when it cannot find a player.
                throw new IllegalStateException("no player to grant '" + spec.paramOr("item", "") + "' to");
            }
            delivered.add(avatar.who() + ":" + spec.paramOr("item", ""));
        });

        quest = Quest.builder(QUEST_ID)
                .objective(ObjectiveDef.builder("step", STEP_KIND)
                        .target(STEP_TARGET).matchMode(MatchMode.EXACT).amount(1).build())
                .reward(RewardSpec.of(KIND, "Item", "Coin_Gold"))
                .autoClaim(false)
                .build();
        engine = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .rewardKinds(kinds)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quest));
    }

    @Nonnull
    private static Subject subjectWith(@Nullable Object handle) {
        return new Subject(UUID.randomUUID(), "tester", handle);
    }

    /** Take the quest and finish it, leaving it parked for a manual collect. */
    private void finish(@Nonnull Subject subject) {
        assertTrue(engine.accept(subject, quest));
        engine.dispatch(subject, STEP_KIND, STEP_TARGET, null, 1L);
        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(subject, quest));
    }

    @Test
    void aHandleThatStandsInForThePlayerIsPaid() {
        Subject subject = subjectWith(new RichHandle(new Avatar("tester")));
        finish(subject);

        assertTrue(engine.claim(subject, quest));

        assertEquals(List.of("tester:Coin_Gold"), delivered);
    }

    @Test
    void aHandleThatAnswersOnlyForItselfIsSilentlyPaidNothing() {
        Subject subject = subjectWith(new OpaqueHandle(new Avatar("tester")));
        finish(subject);

        boolean collected = engine.claim(subject, quest);

        assertTrue(collected, "the collect reports success either way, which is what hides this");
        assertTrue(delivered.isEmpty(),
                "a handler resolving a type the handle will not stand in for pays out nothing");
    }

    @Test
    void aHandleLessSubjectIsPaidNothingEither() {
        Subject subject = Subject.of(UUID.randomUUID(), "tester");
        finish(subject);

        assertTrue(engine.claim(subject, quest));

        assertTrue(delivered.isEmpty(), "there is no player behind a subject with no handle at all");
    }
}
