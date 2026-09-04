package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.FirstClaims;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentPayload;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.SharedCredit;
import com.ziggfreed.common.quest.RequiresGates;
import com.ziggfreed.common.subject.Subject;

/**
 * A boss defeat dispatched once per member under one shared credit is one world first for the
 * whole party: the second and third participants earn the server-first the first one took, and a
 * later party under a later run still loses it.
 */
class SharedCreditDispatchTest {

    private static final String DEFEATED = "ENCOUNTER_DEFEATED";
    private static final String FIRST_KILL = "first_warden_kill";

    private AchievementEngine achievements;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        FirstClaims.resetForTests();
        achievements = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .gates(RequiresGates.of(GateEvaluator.builder().factors(new FactorRegistry("test")).build()))
                .build();
        achievements.setAchievements(List.of(Achievement.builder(FIRST_KILL)
                .serverFirst(true)
                .criterion(ObjectiveDef.builder("0", DEFEATED).target("Kweebec_Warden")
                        .matchMode(MatchMode.EXACT).amount(1).build())
                .build()));
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
        FirstClaims.resetForTests();
    }

    @Nonnull
    private static Moment moment(@Nonnull Subject player, @Nullable MomentPayload payload) {
        return new Moment(DEFEATED, "Kweebec_Warden", null, 1L, null, (Store<EntityStore>) null,
                new Ref<EntityStore>((Store<EntityStore>) null), null, player, player, payload);
    }

    private static SharedCredit run(@Nonnull String key) {
        return () -> key;
    }

    @Test
    void everyMemberOfOneRunEarnsTheServerFirst() {
        Subject alice = Subject.of(UUID.randomUUID(), "alice");
        Subject bob = Subject.of(UUID.randomUUID(), "bob");
        Subject carol = Subject.of(UUID.randomUUID(), "carol");

        ProgressDispatch.produce(null, achievements, moment(alice, run("run-1")));
        ProgressDispatch.produce(null, achievements, moment(bob, run("run-1")));
        ProgressDispatch.produce(null, achievements, moment(carol, run("run-1")));

        assertTrue(achievements.isUnlocked(alice, FIRST_KILL));
        assertTrue(achievements.isUnlocked(bob, FIRST_KILL), "a teammate is not a rival");
        assertTrue(achievements.isUnlocked(carol, FIRST_KILL));
    }

    @Test
    void aLaterRunLosesTheRaceExactlyAsBefore() {
        Subject alice = Subject.of(UUID.randomUUID(), "alice");
        Subject dave = Subject.of(UUID.randomUUID(), "dave");

        ProgressDispatch.produce(null, achievements, moment(alice, run("run-1")));
        ProgressDispatch.produce(null, achievements, moment(dave, run("run-2")));

        assertTrue(achievements.isUnlocked(alice, FIRST_KILL));
        assertFalse(achievements.isUnlocked(dave, FIRST_KILL), "a second party is a second run");
    }

    @Test
    void aMomentWithNoSharedCreditRacesAsBefore() {
        Subject alice = Subject.of(UUID.randomUUID(), "alice");
        Subject bob = Subject.of(UUID.randomUUID(), "bob");

        ProgressDispatch.produce(null, achievements, moment(alice, null));
        ProgressDispatch.produce(null, achievements, moment(bob, null));

        assertTrue(achievements.isUnlocked(alice, FIRST_KILL));
        assertFalse(achievements.isUnlocked(bob, FIRST_KILL));
        assertTrue(FirstClaims.currentSharedCredit() == null, "nothing is left in scope after a dispatch");
    }
}
