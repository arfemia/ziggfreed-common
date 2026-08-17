package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.KillAttribution;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * The attribution half of the kill producer, driven all the way to a real engine: a kill nothing
 * player-shaped landed produces the moment for the player the composed {@link KillAttribution}
 * names, and produces nothing when nobody names one.
 *
 * <p>Each test runs the producer's own steps in the producer's own order - ask the composed
 * attribution for a non-player attacker, and fire for the answer or not at all - over the real
 * {@link ProgressDispatch#produce} and a real {@link QuestEngine}. The ECS half (reading a
 * {@code PlayerRef} off the attacker and off the answer, which is what decides whether the
 * attribution is asked at all and whether its answer is trusted) is what lands behind in-game
 * smoke, as with every other producer. The refs here are store-less stand-ins: the attribution is
 * asked about identity and nothing under test dereferences one.
 */
class KillAttributionProducerTest {

    private static final String KILL_ENTITY = "KILL_ENTITY";

    private final Ref<EntityStore> turret = new Ref<EntityStore>((Store<EntityStore>) null);
    private final Ref<EntityStore> owner = new Ref<EntityStore>((Store<EntityStore>) null);

    private Subject player;
    private QuestEngine quests;
    private AchievementEngine achievements;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        player = Subject.of(UUID.randomUUID(), "owner");
        quests = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        achievements = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    @Nonnull
    private Moment killCreditedTo(@Nonnull Ref<EntityStore> credited) {
        return new Moment(KILL_ENTITY, "Sand_Snake", null, 1L, null, (Store<EntityStore>) null,
                credited, null, player, player, null);
    }

    /**
     * A turret's kill: the attacker carries no {@code PlayerRef}, the registered attribution says it
     * acts for its owner, and the moment fires for the owner - reaching every reaction with the
     * owner as its ref, and advancing the owner's own kill objective.
     */
    @Test
    void aNonPlayerAttackerWithARegisteredAttributionFiresTheMomentForTheAnsweredPlayer() {
        ProgressionRuntime.registrar("yourmod")
                .killAttribution((store, attacker) -> attacker == turret ? owner : null);
        List<Moment> seen = new ArrayList<>();
        ProgressionRuntime.registrar("yourmod").momentListener(seen::add);
        Quest quest = Quest.builder("q_snakes")
                .objective(ObjectiveDef.builder("snakes", KILL_ENTITY).target("Sand_Snake")
                        .matchMode(MatchMode.EXACT).amount(3).build())
                .build();
        quests.setQuests(List.of(quest));
        assertTrue(quests.accept(player, quest));

        Ref<EntityStore> credited = ProgressionRuntime.killAttribution().actsFor(null, turret);
        assertSame(owner, credited, "the seam names the player the turret acts for");
        ProgressDispatch.produce(quests, achievements, killCreditedTo(credited));

        assertEquals(1, seen.size());
        assertSame(owner, seen.get(0).ref(),
                "the reaction sees the OWNER as the credited player, not the turret");
        assertEquals(1, quests.progressOf(player, "q_snakes", "snakes").current(),
                "and the owner's kill objective advanced as if they had landed the blow");
    }

    /** Nothing registered: a non-player attacker credits nobody, which is what a bare server does. */
    @Test
    void withNoAttributionRegisteredANonPlayerAttackerCreditsNobody() {
        List<Moment> seen = new ArrayList<>();
        ProgressionRuntime.registrar("yourmod").momentListener(seen::add);

        Ref<EntityStore> credited = ProgressionRuntime.killAttribution().actsFor(null, turret);

        assertNull(credited, "no answer, so the producer's rule fires nothing");
        assertEquals(0, seen.size(), "and no reaction ever hears of the kill");
    }

    /** Two mods, two kinds of spawned thing: each attributes its own and neither has to know. */
    @Test
    void theFirstRealAnswerWinsAndAnUnansweredAttackerFallsThrough() {
        Ref<EntityStore> pet = new Ref<EntityStore>((Store<EntityStore>) null);
        Ref<EntityStore> petOwner = new Ref<EntityStore>((Store<EntityStore>) null);
        ProgressionRuntime.registrar("turretmod")
                .killAttribution((store, attacker) -> attacker == turret ? owner : null);
        ProgressionRuntime.registrar("petmod")
                .killAttribution((store, attacker) -> attacker == pet ? petOwner : null);

        KillAttribution composed = ProgressionRuntime.killAttribution();
        assertSame(owner, composed.actsFor(null, turret));
        assertSame(petOwner, composed.actsFor(null, pet));
        assertNull(composed.actsFor(null, new Ref<EntityStore>((Store<EntityStore>) null)),
                "an attacker nobody claims credits nobody");
    }

    /** One mod's broken resolver is skipped with a warn; the next is still asked. */
    @Test
    void aThrowingAttributionIsSkippedAndTheNextIsAsked() {
        List<String> warnings = new ArrayList<>();
        ProgressionRuntime.registrar("brokenmod")
                .warn(warnings::add)
                .killAttribution((store, attacker) -> {
                    throw new IllegalStateException("boom");
                });
        ProgressionRuntime.registrar("yourmod")
                .killAttribution((store, attacker) -> attacker == turret ? owner : null);

        assertSame(owner, ProgressionRuntime.killAttribution().actsFor(null, turret));
        assertEquals(1, warnings.size(), "the broken one is reported once per ask");
    }
}
