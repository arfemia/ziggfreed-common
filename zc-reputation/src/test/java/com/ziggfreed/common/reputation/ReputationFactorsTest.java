package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.reputation.asset.ReputationConfig;

/**
 * The three readings: effective standing, earned standing and a rank reached by effective standing; a
 * known untouched reputation reads its starting value; everything they cannot answer is null.
 */
class ReputationFactorsTest {

    private FakeReputationNative engine;
    private ReputationService service;

    @BeforeEach
    void seed() {
        ReputationFixtures.reset();
        engine = ReputationFixtures.engine();
        service = new ReputationService(engine, ReputationFanOut.NONE);
        ReputationFixtures.loadCompanions(Map.of("test_old_jack", ReputationFixtures.companion(
                ReputationFixtures.OLD_JACK, "{ \"Gear\": { \"Stat\": \"Reputation_Test\" } }")));
    }

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
    }

    private static FactorContext ask(String param) {
        return FactorContext.builder().param(param).build();
    }

    @Test
    void standingIsEffectiveAndEarnedLeavesGearOut() {
        engine.stored.put("Test_Old_Jack", 500);
        engine.stats.put("Reputation_Test", 600L);
        assertEquals(1_100.0, ReputationFactors.standing(service, ask("Test_Old_Jack")));
        assertEquals(500.0, ReputationFactors.earned(service, ask("Test_Old_Jack")));
    }

    @Test
    void aRankReadsOneAtOrAboveItsFloorByEffectiveStanding() {
        engine.stored.put("Test_Old_Jack", 900);
        engine.stats.put("Reputation_Test", 100L);
        assertEquals(1.0, ReputationFactors.rank(service, ask("Test_Old_Jack/Friendly")), "900 earned + 100 gear");
        assertEquals(1.0, ReputationFactors.rank(service, ask("test_old_jack/friendly")), "ids in any case");
        assertEquals(0.0, ReputationFactors.rank(service, ask("Test_Old_Jack/Honored")));
        assertEquals(1.0, ReputationFactors.rank(service, ask("Test_Old_Jack/Neutral")), "a lower rank also holds");
    }

    @Test
    void anUntouchedReputationReadsItsStartingValue() {
        engine.group("Test_Welcome", 500);
        assertEquals(500.0, ReputationFactors.standing(service, ask("Test_Welcome")));
    }

    @Test
    void everyCaseItCannotAnswerIsNull() {
        assertNull(ReputationFactors.standing(service, ask("Nobody")));
        assertNull(ReputationFactors.standing(service, ask(null)));
        assertNull(ReputationFactors.standing(service, ask(" ")));
        assertNull(ReputationFactors.rank(service, ask("Test_Old_Jack/Champion")), "an unknown rank");
        assertNull(ReputationFactors.rank(service, ask("Test_Old_Jack")), "no rank named");
        assertNull(ReputationFactors.rank(service, ask("/Friendly")));
        assertNull(ReputationFactors.rank(service, ask("Test_Old_Jack/")));
        engine.live = false;
        assertNull(ReputationFactors.standing(service, ask("Test_Old_Jack")), "no live player");
        engine.live = true;
        ReputationConfig.getInstance().setGlobalEnabled(false);
        assertNull(ReputationFactors.earned(service, ask("Test_Old_Jack")), "the module switched off");
        ReputationConfig.getInstance().setGlobalEnabled(true);
        ReputationFixtures.loadCompanions(Map.of("test_old_jack", ReputationFixtures.companion(
                ReputationFixtures.OLD_JACK, "{ \"Enabled\": false }")));
        assertNull(ReputationFactors.standing(service, ask("Test_Old_Jack")), "the reputation switched off");
        ReputationFixtures.reset();
        engine.ranks.clear();
        engine.ranks.add(new ReputationLadder.Rank("Only", -10, 10));
        assertNull(ReputationFactors.rank(service, ask("Test_Old_Jack/Only")), "a ladder of one rank answers no rank");
        assertEquals(0.0, ReputationFactors.standing(service, ask("Test_Old_Jack")), "while standing still reads");
    }

    @Test
    void theThreeIdsAreContributedProcessWide() {
        ReputationFactors.contribute(service);
        assertTrue(FactorContributions.isContributed(ReputationFactors.STANDING));
        assertTrue(FactorContributions.isContributed(ReputationFactors.EARNED));
        assertTrue(FactorContributions.isContributed(ReputationFactors.RANK));
    }

    @Test
    void eachReadingShipsANameOverlay() throws IOException {
        Path factors = Path.of("src", "main", "resources", "Server", "ZiggfreedCommon", "Factors");
        Set<String> named = new TreeSet<>();
        try (Stream<Path> files = Files.list(factors)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                named.add(json.get("Factor").getAsString());
                assertTrue(json.getAsJsonObject("Text").has("TitleKey"), file + " names its factor");
            }
        }
        assertEquals(new TreeSet<>(Set.of(ReputationFactors.STANDING, ReputationFactors.EARNED, ReputationFactors.RANK)),
                named);
    }
}
