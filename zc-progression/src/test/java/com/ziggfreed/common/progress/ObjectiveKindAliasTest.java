package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.progress.runtime.ProgressionIcons;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.quest.asset.QuestObjectiveAsset;
import com.ziggfreed.common.subject.Subject;

/**
 * The objective-kind ALIAS: an authored kind that runs as another with its target rewritten.
 *
 * <p>The invariant under test is the split between the two pairs on one {@link ObjectiveDef}: the
 * engine dispatches, indexes and settles on the desugared pair, and every text and icon surface
 * composes from the authored pair. Each half is pinned against the other, because the failure on
 * either side is silent - a step that never progresses, or a sentence naming a channel id.
 */
class ObjectiveKindAliasTest {

    private static final String AUTHORED = "REACH_RANK";
    private static final String NS = "ziggfreedcommon.progress.";

    private ObjectiveKindRegistry kinds;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        kinds = new ObjectiveKindRegistry("alias-test");
        kinds.alias(AUTHORED, ObjectiveKindRegistry.STAT_THRESHOLD, "yourmod", target -> "Rank_" + target);
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
        ObjectiveComposer.install((objective, key) -> null);
    }

    private ObjectiveDef aliasedStep() {
        return QuestObjectiveAsset.of(AUTHORED, "Mining", 30L).toDefBuilder("climb", kinds).build();
    }

    // ==================== the registry ====================

    @Test
    void anAliasIsLookedUpWithoutRegardToCaseAndIsNotAKind() {
        ObjectiveKindRegistry.Alias alias = kinds.alias("reach_rank");

        assertNotNull(alias);
        assertEquals(AUTHORED, alias.authoredKind());
        assertEquals(ObjectiveKindRegistry.STAT_THRESHOLD, alias.runsAs());
        assertEquals("yourmod", alias.owner());
        assertEquals("Rank_Mining", alias.rewriteTarget("Mining"));
        assertTrue(kinds.isAlias(AUTHORED));
        assertEquals(List.of(AUTHORED), kinds.aliasIds());
        assertFalse(kinds.isRegistered(AUTHORED), "an alias is sugar, not an entry in the vocabulary");
        assertFalse(kinds.ids().contains(AUTHORED));
        assertNull(kinds.alias("PICKUP_ITEM"), "an ordinary kind is not an alias");
    }

    @Test
    void registrationIsIdempotentAndTheLastWriteWins() {
        kinds.alias(AUTHORED, ObjectiveKindRegistry.STAT_THRESHOLD, "yourmod", target -> "Other_" + target);
        assertEquals(List.of(AUTHORED), kinds.aliasIds(), "one alias, however many times it is registered");
        assertEquals("Other_Mining", kinds.alias(AUTHORED).rewriteTarget("Mining"));

        kinds.alias("", "STAT_THRESHOLD", "yourmod", null);
        kinds.alias(AUTHORED, " ", "yourmod", null);
        assertEquals(1, kinds.aliasIds().size(), "a blank id on either side is ignored");
    }

    @Test
    void aRewriteThatFailsOrAnswersNullKeepsTheAuthoredTarget() {
        kinds.alias("BROKEN", ObjectiveKindRegistry.STAT_THRESHOLD, "yourmod", target -> {
            throw new IllegalStateException("a consumer bug");
        });
        kinds.alias("SILENT", ObjectiveKindRegistry.STAT_THRESHOLD, "yourmod", target -> null);

        assertEquals("Mining", kinds.alias("BROKEN").rewriteTarget("Mining"));
        assertEquals("Mining", kinds.alias("SILENT").rewriteTarget("Mining"));
        assertEquals("", kinds.alias("SILENT").rewriteTarget(null), "no target named stays no target");
    }

    @Test
    void clearDropsTheAliasesWithEverythingElse() {
        kinds.clear();
        assertNull(kinds.alias(AUTHORED));
        assertTrue(kinds.aliasIds().isEmpty());
    }

    @Test
    void aliasingAProducibleRegisteredKindIsAllowedAndTheAliasStillFires() {
        kinds.register("SOMETHING", "yourmod", false, true);
        kinds.alias("SOMETHING", "PICKUP_ITEM", "yourmod", null);

        assertNotNull(kinds.alias("SOMETHING"), "reported at registration, not refused");
        assertEquals("PICKUP_ITEM",
                QuestObjectiveAsset.of("SOMETHING", "Ore", 1L).toDefBuilder("s", kinds).build().kind());
    }

    // ==================== the fold ====================

    @Test
    void theFoldBuildsTheRunPairForTheEngineAndKeepsTheAuthoredPairBesideIt() {
        ObjectiveDef step = aliasedStep();

        assertEquals(ObjectiveKindRegistry.STAT_THRESHOLD, step.kind());
        assertEquals("Rank_Mining", step.target());
        assertEquals(AUTHORED, step.authoredKind());
        assertEquals("Mining", step.authoredTarget());
    }

    @Test
    void aNullRegistryAppliesNoAliasAndAnOrdinaryStepHasOnePair() {
        ObjectiveDef unaliased = QuestObjectiveAsset.of(AUTHORED, "Mining", 30L).toDefBuilder("climb", null).build();
        assertEquals(AUTHORED, unaliased.kind());
        assertEquals("Mining", unaliased.target());

        ObjectiveDef plain = QuestObjectiveAsset.of("PICKUP_ITEM", "Ore", 1L).toDefBuilder("collect", kinds).build();
        assertEquals(plain.kind(), plain.authoredKind());
        assertEquals(plain.target(), plain.authoredTarget());
    }

    @Test
    void theOneArgumentFoldReadsTheSharedVocabulary() {
        ProgressionRuntime.objectiveKinds().alias(AUTHORED, ObjectiveKindRegistry.STAT_THRESHOLD, "yourmod",
                target -> "Rank_" + target);

        ObjectiveDef step = QuestObjectiveAsset.of(AUTHORED, "Mining", 30L).toDef("climb", null);

        assertEquals(ObjectiveKindRegistry.STAT_THRESHOLD, step.kind());
        assertEquals("Rank_Mining", step.target());
    }

    // ==================== the engine: dispatch on the desugared pair ====================

    @Test
    void theEngineSettlesTheStepOnTheRunPairAndNeverOnTheAuthoredOne() {
        Quest quest = Quest.builder("climb_high").objective(aliasedStep()).build();
        QuestEngine engine = QuestEngine.builder()
                .objectiveKinds(kinds)
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quest));
        Subject player = Subject.of(UUID.randomUUID(), "tester");
        assertTrue(engine.accept(player, quest));

        engine.dispatch(player, AUTHORED, "Mining", null, 30L);
        assertEquals(QuestStatus.ACTIVE, engine.status(player, quest),
                "the authored pair is nothing the engine indexes: nobody fires it");

        engine.dispatch(player, ObjectiveKindRegistry.STAT_THRESHOLD, "Rank_Mining", null, 30L);
        assertEquals(QuestStatus.COMPLETED, engine.status(player, quest),
                "the run pair is what the value-threshold producer fires, and it settles the step");
    }

    // ==================== text and icon: the authored pair ====================

    @Test
    void theNeutralSentenceIsComposedFromTheAuthoredKindAndTarget() {
        Set<String> shipped = Set.of(NS + "objective.reach_rank", NS + "objective.stat_threshold");
        NeutralObjectiveComposer composer = new NeutralObjectiveComposer(shipped::contains, kindId -> null);

        Message line = composer.compose(aliasedStep(), null);

        assertNotNull(line);
        assertEquals(NS + "objective.reach_rank", line.getMessageId(),
                "the convention key is the AUTHORED kind's, not the threshold's");
    }

    @Test
    void theAuthoredKindsOwnPresentationPicturesTheStepNotTheRunKinds() {
        ObjectiveKindRegistry shared = ProgressionRuntime.objectiveKinds();
        shared.alias(AUTHORED, ObjectiveKindRegistry.STAT_THRESHOLD, "yourmod", target -> "Rank_" + target);
        shared.register("yourmod", new ObjectiveKind(AUTHORED, true, false).withPresentation(
                new ObjectiveKind.Presentation(null, IconSpec.ofItem("Deco_Scroll"), Map.of())));
        ObjectiveDef step = QuestObjectiveAsset.of(AUTHORED, "Mining", 30L).toDef("climb", null);
        assertEquals(ObjectiveKindRegistry.STAT_THRESHOLD, step.kind(), "still runs as the threshold");

        IconSpec icon = ProgressionIcons.forObjective("climb_high", step);

        assertNotNull(icon, "the picture is looked up under the authored kind");
        assertEquals("Deco_Scroll", icon.itemId());
        assertSame(shared, ProgressionRuntime.objectiveKinds());
    }
}
