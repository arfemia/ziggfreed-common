package com.ziggfreed.common.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.StatThresholdProbe;
import com.ziggfreed.common.subject.Subject;

/**
 * The always-on engine's half of the standing-value kind: it re-reads the channel at SELF-HEAL and
 * nowhere else, which is the deliberate difference from the quest engine (see
 * {@link AchievementEngine#refreshStatThresholds}). Everything else about the kind - high-water
 * arithmetic, an unreadable value writing nothing, the unwired seam - must behave identically.
 */
class AchievementStatThresholdTest {

    private static final String CHANNEL = "Deep_Delving";
    private static final Subject ALICE = new Subject(new UUID(0, 1), "Alice", null);

    private Map<String, Double> channels;

    @BeforeEach
    void setUp() {
        channels = new HashMap<>();
    }

    @Nonnull
    private FactorRegistry factors() {
        FactorRegistry registry = new FactorRegistry("test");
        // No asset-defined layer in a unit run: the answers must come from this map alone.
        registry.derivedSource(null);
        registry.register(StatThresholdProbe.STAT_FACTOR, "test",
                ctx -> ctx.param() == null ? null : channels.get(ctx.param()));
        return registry;
    }

    @Nonnull
    private AchievementEngine.Builder unwired() {
        return AchievementEngine.builder()
                .nativeEvents(false)
                .warn(message -> { });
    }

    @Nonnull
    private AchievementEngine wired() {
        return unwired()
                .factors(factors())
                .factorContext(subject -> FactorContext.builder().payload(subject.id()).build())
                .build();
    }

    @Nonnull
    private static Achievement thresholdAchievement(@Nonnull String id, long amount) {
        return Achievement.builder(id)
                .criterion(ObjectiveDef.builder("0", ObjectiveKindRegistry.STAT_THRESHOLD)
                        .target(CHANNEL).matchMode(MatchMode.EXACT).amount(amount).build())
                .build();
    }

    @Test
    void selfHealEarnsAThresholdTheSubjectAlreadyMeets() {
        channels.put(CHANNEL, 25d);
        AchievementEngine engine = wired();
        engine.setAchievements(List.of(thresholdAchievement("deep_one", 20)));

        assertTrue(engine.selfHeal(ALICE) > 0);

        assertTrue(engine.isUnlocked(ALICE, "deep_one"),
                "the criterion is met in the same pass that re-reads it");
    }

    @Test
    void aPartialReadingIsRecordedWithoutEarningAnything() {
        channels.put(CHANNEL, 8d);
        AchievementEngine engine = wired();
        Achievement achievement = thresholdAchievement("deep_one", 20);
        engine.setAchievements(List.of(achievement));

        engine.selfHeal(ALICE);

        assertEquals(8, engine.progressOf(ALICE, achievement, 0).current());
        assertFalse(engine.isUnlocked(ALICE, "deep_one"));
    }

    @Test
    void aLaterLowerReadingLeavesRecordedProgressAlone() {
        channels.put(CHANNEL, 8d);
        AchievementEngine engine = wired();
        Achievement achievement = thresholdAchievement("deep_one", 20);
        engine.setAchievements(List.of(achievement));
        engine.selfHeal(ALICE);

        channels.put(CHANNEL, 3d);
        engine.selfHeal(ALICE);

        assertEquals(8, engine.progressOf(ALICE, achievement, 0).current());
    }

    @Test
    void aChannelNothingCanAnswerWritesNothingRatherThanResetting() {
        channels.put(CHANNEL, 8d);
        AchievementEngine engine = wired();
        Achievement achievement = thresholdAchievement("deep_one", 20);
        engine.setAchievements(List.of(achievement));
        engine.selfHeal(ALICE);

        channels.remove(CHANNEL);
        engine.selfHeal(ALICE);

        assertEquals(8, engine.progressOf(ALICE, achievement, 0).current());
        assertFalse(engine.isUnlocked(ALICE, "deep_one"));
    }

    @Test
    void aRefusedProgressGateStopsTheReadingBeingRecordedAtAll() {
        channels.put(CHANNEL, 25d);
        AchievementEngine engine = unwired()
                .factors(factors())
                .factorContext(subject -> FactorContext.builder().payload(subject.id()).build())
                .gates(new AchievementGates() {
                    @Override
                    public boolean canProgress(@Nonnull Subject subject,
                                               @Nonnull Achievement achievement) {
                        return false;
                    }
                })
                .build();
        Achievement achievement = thresholdAchievement("deep_one", 20);
        engine.setAchievements(List.of(achievement));

        engine.selfHeal(ALICE);

        assertEquals(0, engine.progressOf(ALICE, achievement, 0).current());
        assertFalse(engine.isUnlocked(ALICE, "deep_one"));
    }

    @Test
    void withNoFactorVocabularyTheKindIsPurelyConsumerFired() {
        channels.put(CHANNEL, 25d);
        AchievementEngine engine = unwired().build();
        Achievement achievement = thresholdAchievement("deep_one", 20);
        engine.setAchievements(List.of(achievement));

        engine.selfHeal(ALICE);
        assertEquals(0, engine.progressOf(ALICE, achievement, 0).current(),
                "nothing is read for itself without a vocabulary to read it through");

        engine.dispatch(ALICE, ObjectiveKindRegistry.STAT_THRESHOLD, CHANNEL, null, 12);
        assertEquals(12, engine.progressOf(ALICE, achievement, 0).current());

        engine.dispatch(ALICE, ObjectiveKindRegistry.STAT_THRESHOLD, CHANNEL, null, 5);
        assertEquals(12, engine.progressOf(ALICE, achievement, 0).current(),
                "the kind is value-based, so a lower fire is not added on top");

        engine.dispatch(ALICE, ObjectiveKindRegistry.STAT_THRESHOLD, CHANNEL, null, 20);
        assertTrue(engine.isUnlocked(ALICE, "deep_one"));
    }
}
