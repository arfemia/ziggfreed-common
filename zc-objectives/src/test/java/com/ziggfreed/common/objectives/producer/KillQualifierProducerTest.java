package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.KillQualifier;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * The qualifier half of the kill producer, driven the way {@link KillAttributionProducerTest}
 * drives the attribution half: the producer asks the composed {@link KillQualifier} ONCE at fire
 * time and stamps the answer into the ONE primary {@code KILL_ENTITY} dispatch, so a criterion
 * authoring that qualifier matches, an unqualified criterion keeps matching every kill (an empty
 * AUTHORED qualifier reads as "any"), and no second dispatch ever exists to double-count either.
 *
 * <p>Each test runs the producer's own steps in the producer's own order - ask the composed
 * qualifier for the victim, fire one moment carrying the answer - over the real
 * {@link ProgressDispatch#produce} and real engines. The refs are store-less stand-ins: the
 * qualifier is asked about identity and nothing under test dereferences one.
 */
class KillQualifierProducerTest {

    private static final String KILL_ENTITY = "KILL_ENTITY";

    private final Ref<EntityStore> victim = new Ref<EntityStore>((Store<EntityStore>) null);

    private Subject player;
    private QuestEngine quests;
    private AchievementEngine achievements;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        player = Subject.of(UUID.randomUUID(), "slayer");
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

    /** One kill, exactly as the producer fires it: the composed answer stamped as the qualifier. */
    @Nonnull
    private Moment oneKill(@Nullable String qualifier) {
        return new Moment(KILL_ENTITY, "Sand_Snake", qualifier, 1L, null, (Store<EntityStore>) null,
                victim, null, player, player, null);
    }

    @Nonnull
    private Quest killQuest(@Nonnull String id, @Nullable String authoredQualifier) {
        return Quest.builder(id)
                .objective(ObjectiveDef.builder("snakes", KILL_ENTITY).target("Sand_Snake")
                        .matchMode(MatchMode.EXACT).qualifier(authoredQualifier).amount(3).build())
                .build();
    }

    /** The registered answer rides the moment: every reaction and both engines see it stamped. */
    @Test
    void aRegisteredQualifierIsStampedIntoTheOneDispatch() {
        ProgressionRuntime.registrar("scalingmod")
                .killQualifier((store, victimRef) -> victimRef == victim ? "Legendary" : null);
        List<Moment> seen = new ArrayList<>();
        ProgressionRuntime.registrar("yourmod").momentListener(seen::add);

        String qualifier = ProgressionRuntime.killQualifier().qualifierFor(null, victim);
        assertEquals("Legendary", qualifier, "the seam names what the killed entity carries");
        ProgressDispatch.produce(quests, achievements, oneKill(qualifier));

        assertEquals(1, seen.size(), "ONE dispatch per kill, qualified or not");
        assertEquals("Legendary", seen.get(0).qualifier(),
                "and the reaction sees the qualifier stamped on that one moment");
    }

    /** Nothing registered: the ask answers null and the kill fires unqualified, as always. */
    @Test
    void withNoQualifierRegisteredTheKillFiresUnqualified() {
        List<Moment> seen = new ArrayList<>();
        ProgressionRuntime.registrar("yourmod").momentListener(seen::add);

        String qualifier = ProgressionRuntime.killQualifier().qualifierFor(null, victim);
        assertNull(qualifier, "no contribution, no answer");
        ProgressDispatch.produce(quests, achievements, oneKill(qualifier));

        assertEquals(1, seen.size());
        assertNull(seen.get(0).qualifier(), "the moment is byte-identical to a bare server's");
    }

    /**
     * The no-double-count rule, by construction: an unqualified criterion (null authored qualifier)
     * matches a QUALIFIED and an UNQUALIFIED kill alike, off the single dispatch each kill gets -
     * so stamping the qualifier costs no ordinary kill quest anything, and a second qualified
     * re-fire would be exactly the double count this design refuses.
     */
    @Test
    void anUnqualifiedCriterionMatchesQualifiedAndUnqualifiedKillsOnceEach() {
        Quest anySnake = killQuest("q_any_snake", null);
        quests.setQuests(List.of(anySnake));
        quests.accept(player, anySnake);

        ProgressDispatch.produce(quests, achievements, oneKill("Legendary"));
        assertEquals(1, quests.progressOf(player, "q_any_snake", "snakes").current(),
                "a qualified kill advances the unqualified criterion exactly once");

        ProgressDispatch.produce(quests, achievements, oneKill(null));
        assertEquals(2, quests.progressOf(player, "q_any_snake", "snakes").current(),
                "and an unqualified kill advances it exactly once too");
    }

    /** A criterion authoring a qualifier matches only the kill that carries it. */
    @Test
    void aQualifiedCriterionMatchesOnlyTheMatchingQualifiedKill() {
        Quest legendOnly = killQuest("q_legend", "Legendary");
        quests.setQuests(List.of(legendOnly));
        quests.accept(player, legendOnly);

        ProgressDispatch.produce(quests, achievements, oneKill(null));
        assertEquals(0, quests.progressOf(player, "q_legend", "snakes").current(),
                "an unqualified kill never advances a criterion that names a qualifier");

        ProgressDispatch.produce(quests, achievements, oneKill("Rare"));
        assertEquals(0, quests.progressOf(player, "q_legend", "snakes").current(),
                "and neither does a kill carrying a different one");

        ProgressDispatch.produce(quests, achievements, oneKill("Legendary"));
        assertEquals(1, quests.progressOf(player, "q_legend", "snakes").current(),
                "only the matching qualified kill counts");
    }

    /** One mod's broken resolver is skipped with a warn; the next is still asked. */
    @Test
    void aThrowingQualifierIsSkippedAndTheNextIsAsked() {
        List<String> warnings = new ArrayList<>();
        ProgressionRuntime.registrar("brokenmod")
                .warn(warnings::add)
                .killQualifier((store, victimRef) -> {
                    throw new IllegalStateException("boom");
                });
        ProgressionRuntime.registrar("scalingmod")
                .killQualifier((store, victimRef) -> victimRef == victim ? "Epic" : null);

        assertEquals("Epic", ProgressionRuntime.killQualifier().qualifierFor(null, victim));
        assertEquals(1, warnings.size(), "the broken one is reported once per ask");
    }
}
