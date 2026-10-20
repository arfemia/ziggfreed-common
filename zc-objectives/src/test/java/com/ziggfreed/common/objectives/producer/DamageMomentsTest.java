package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentPayload;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSubjectSource;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.source.TrackSources;

/**
 * One hit, one count (SK5d): the damage producer's route is the only fire of {@code DEAL_DAMAGE}, so a quest
 * objective and an achievement criterion on damage each advance by the hit's whole amount exactly once, and every
 * moment listener (a track source, the stat store) is told once, with the same amount. A consumer firing the kind
 * into the engines beside it would double the engines' count, which is why none does.
 */
class DamageMomentsTest {

    private record HitPayload(double dealt) implements MomentPayload {
    }

    private Subject player;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        player = Subject.of(UUID.randomUUID(), "tester");
        ProgressionRuntime.registrar("yourmod")
                .subjects(new ProgressionSubjectSource() {
                    @Override
                    @Nullable
                    public Subject questSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> r) {
                        return player;
                    }

                    @Override
                    @Nullable
                    public Subject achievementSubject(@Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> r) {
                        return player;
                    }
                })
                .warn(message -> { });
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    @Nonnull
    private static ObjectiveDef damage(@Nonnull String id, long amount) {
        return ObjectiveDef.builder(id, TrackSources.DEAL_DAMAGE)
                .target("").matchMode(MatchMode.CONTAINS).amount(amount).build();
    }

    @Test
    void aHitCountsOnceForTheQuestTheAchievementAndEveryListener() {
        List<Moment> heard = new ArrayList<>();
        ProgressionRuntime.registrar("yourmod").momentListener(heard::add);
        Quest quest = Quest.builder("q_hold_the_stair").objective(damage("hold", 1000)).build();
        Achievement achievement = Achievement.builder("a_hard_hitter").criterion(damage("0", 10000)).build();
        ProgressionRuntime.publishQuests("yourmod", List.of(quest));
        ProgressionRuntime.publishAchievements("yourmod", List.of(achievement));
        QuestEngine quests = ProgressionRuntime.quests();
        AchievementEngine achievements = ProgressionRuntime.achievements();
        assertTrue(quests.accept(player, quest));
        Ref<EntityStore> ref = new Ref<EntityStore>((Store<EntityStore>) null);
        DamageHit hit = new DamageHit(null, 0, null, ref, ref, ref, null);

        DamageMoments.fire(hit, null, "Trork_Grunt", "Weapon_Sword_Iron", 12.4, new HitPayload(12.4));

        assertEquals(12, quests.progressOf(player, "q_hold_the_stair", "hold").current(),
                "the quest objective took the hit once, at its whole amount");
        assertEquals(12, achievements.progressOf(player, achievement, 0).current(),
                "and so did the achievement criterion");
        assertEquals(1, heard.size(), "every listener heard the hit once");
        assertEquals(TrackSources.DEAL_DAMAGE, heard.get(0).kindId());
        assertEquals(12L, heard.get(0).amount());
        assertEquals("Trork_Grunt", heard.get(0).target());

        DamageMoments.fire(hit, null, "Trork_Grunt", null, 7.6, new HitPayload(7.6));

        assertEquals(20, quests.progressOf(player, "q_hold_the_stair", "hold").current(),
                "a second hit adds its own amount, never the first one again");
        assertEquals(20, achievements.progressOf(player, achievement, 0).current());
        assertEquals(2, heard.size());

        DamageHitFilters.register("yourmod", excluded -> true);
        try {
            DamageMoments.fire(hit, null, "Training_Dummy", null, 50.0, new HitPayload(50.0));
        } finally {
            DamageHitFilters.unregister("yourmod");
        }

        assertEquals(20, quests.progressOf(player, "q_hold_the_stair", "hold").current(),
                "a hit a consumer's filter keeps out counts for no quest");
        assertEquals(20, achievements.progressOf(player, achievement, 0).current(), "no achievement");
        assertEquals(2, heard.size(), "and reaches no listener");
    }
}
