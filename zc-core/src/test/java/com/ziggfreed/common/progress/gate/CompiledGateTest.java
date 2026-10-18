package com.ziggfreed.common.progress.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.DerivedFactorConfig;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.subject.Subject;

/**
 * A compiled gate keeps a verdict only where its readings say it may (each factor's Factors file
 * {@code Volatility}), and lets it go the moment anything it read could have moved: the server epoch, a
 * player's own epoch, the vocabulary generation. Nothing is kept before the server has booted.
 */
class CompiledGateTest {

    private static final String RANK = "yourmod:rank";

    private final AtomicInteger asks = new AtomicInteger();
    private final Subject player = Subject.of(UUID.randomUUID(), "tester");
    private FactorRegistry vocabulary;
    private GateEvaluator evaluator;

    @BeforeEach
    void setUp() {
        GateEpochs.resetForTests();
        GateEpochs.keepForTests();
        vocabulary = new FactorRegistry("compiled-gate-test");
        vocabulary.register(RANK, ctx -> {
            asks.incrementAndGet();
            return 10.0;
        });
        evaluator = GateEvaluator.builder().factors(vocabulary).build();
    }

    @AfterEach
    void tearDown() {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of());
        GateEpochs.resetForTests();
    }

    /** The factor's Factors file declaring {@code volatility} (null declares nothing). */
    private static void declare(String volatility) {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of("rank_traits",
                DerivedFactorAsset.of("rank_traits", null, RANK, null, null, null, null, null, volatility)));
    }

    private static GateSpec atLeast(double min) {
        return GateSpec.of(new FactorCondition[] {FactorCondition.of(RANK, null, min, null)},
                null, null, null, null, null, null);
    }

    @Test
    void aMomentReadingIsAskedEveryTime() {
        declare(null);
        GateSpec gate = atLeast(5);

        assertTrue(evaluator.check(gate, GateQuery.of(player)).passed());
        assertTrue(evaluator.check(gate, GateQuery.of(player)).passed());
        assertEquals(2, asks.get(), "a reading nobody declared is Moment, never kept");
    }

    @Test
    void aServerReadingIsKeptUntilTheServerEpochMoves() {
        declare("Server");
        GateSpec gate = atLeast(5);

        evaluator.check(gate, GateQuery.of(player));
        evaluator.check(gate, GateQuery.of(Subject.of(UUID.randomUUID(), "other")));
        assertEquals(1, asks.get(), "one verdict for everyone while nothing moved");

        GateEpochs.bumpServer();
        evaluator.check(gate, GateQuery.of(player));
        assertEquals(2, asks.get(), "a moved server epoch reads it again");
    }

    @Test
    void aPlayerReadingIsKeptPerPlayerUntilTheirOwnEpochMoves() {
        declare("Player");
        GateSpec gate = atLeast(5);

        evaluator.check(gate, GateQuery.of(player));
        evaluator.check(gate, GateQuery.of(player));
        assertEquals(1, asks.get(), "kept for the player while their epoch stands");

        evaluator.check(gate, GateQuery.of(Subject.of(UUID.randomUUID(), "other")));
        assertEquals(2, asks.get(), "another player has a verdict of their own");

        GateEpochs.bumpPlayer(player.id());
        evaluator.check(gate, GateQuery.of(player));
        assertEquals(3, asks.get(), "a moment of their own reads it again");
    }

    @Test
    void aReplacedProviderIsPickedUpThroughTheGeneration() {
        declare("Server");
        GateSpec gate = atLeast(5);
        assertTrue(evaluator.check(gate, GateQuery.of(player)).passed());

        vocabulary.register(RANK, ctx -> 1.0);

        GateVerdict after = evaluator.check(gate, GateQuery.of(player));
        assertFalse(after.passed(), "a new registration moves the generation, so the kept provider and verdict go");
        assertEquals(GateEvaluator.REASON_FACTOR + RANK, after.firstFailure());
    }

    @Test
    void aFactorsReloadDropsWhatWasKept() {
        declare("Server");
        GateSpec gate = atLeast(5);
        evaluator.check(gate, GateQuery.of(player));

        declare("Moment");
        evaluator.check(gate, GateQuery.of(player));
        evaluator.check(gate, GateQuery.of(player));
        assertEquals(3, asks.get(), "a reload that makes the reading Moment stops keeping it at once");
    }

    @Test
    void nothingIsKeptBeforeTheServerHasBooted() {
        GateEpochs.resetForTests();
        declare("Server");
        GateSpec gate = atLeast(5);

        evaluator.check(gate, GateQuery.of(player));
        evaluator.check(gate, GateQuery.of(player));
        assertEquals(2, asks.get(), "a setup-time ask could outlive what it read");
    }

    @Test
    void aLocalVocabularyAnswersFirstAndIsNeverKept() {
        declare("Server");
        FactorRegistry session = new FactorRegistry("session");
        session.register(RANK, ctx -> 2.0);
        GateSpec gate = atLeast(5);

        assertFalse(evaluator.check(gate, GateQuery.builder().subject(player).local(session).build()).passed(),
                "the session's own reading wins over the shared one");
        assertTrue(evaluator.check(gate, GateQuery.of(player)).passed(),
                "and the session's answer was never kept for anyone");
    }
}
