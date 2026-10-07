package com.ziggfreed.common.reputation.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.reputation.FakeReputationNative;
import com.ziggfreed.common.reputation.ReputationFanOut;
import com.ziggfreed.common.reputation.ReputationFixtures;
import com.ziggfreed.common.reputation.ReputationService;

/**
 * What the page shows, decided where a test can reach it: one row per met reputation with the rank above
 * it or, past the top, the next Beyond reward; the pick; the row's words; the detail lines.
 */
class ReputationViewTest {

    private FakeReputationNative engine;
    private ReputationService service;

    @BeforeEach
    void seed() {
        ReputationFixtures.reset();
        engine = ReputationFixtures.engine().group("Test_Hallowed", 0);
        service = new ReputationService(engine, ReputationFanOut.NONE);
        ReputationFixtures.loadCompanions(Map.of(
                "test_old_jack", ReputationFixtures.companion(ReputationFixtures.OLD_JACK, """
                        { "Text": { "TitleKey": "test.jack.name", "FlavorKey": "test.jack.desc" },
                          "Gear": { "Stat": "Reputation_Test" }, "Cap": 21000, "Order": 1 }
                        """),
                "test_hallowed", ReputationFixtures.companion("Test_Hallowed",
                        "{ \"Order\": 2, \"Beyond\": { \"Every\": 5000 } }")));
    }

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
    }

    private List<ReputationView.Row> rows() {
        return ReputationView.rows(service.met(null, null), service::ladderFor);
    }

    private static List<String> ids(List<Message> lines) {
        return lines.stream().map(Message::getMessageId).toList();
    }

    @Test
    void eachMetReputationIsARowWithItsNextRankOrItsNextReward() {
        engine.stored.put("Test_Old_Jack", 1_500);
        engine.stored.put("Test_Hallowed", 25_000);
        List<ReputationView.Row> rows = rows();
        assertEquals(List.of("Test_Old_Jack", "Test_Hallowed"), rows.stream().map(ReputationView.Row::id).toList());
        assertEquals("Honored", rows.get(0).next().id());
        assertNull(rows.get(0).nextReward());
        assertNull(rows.get(1).next(), "nothing above the top");
        assertEquals(Long.valueOf(26_000), rows.get(1).nextReward());
    }

    @Test
    void aPickMatchesWithoutRegardToCaseElseTakesTheFirst() {
        engine.stored.put("Test_Old_Jack", 10);
        engine.stored.put("Test_Hallowed", 10);
        List<ReputationView.Row> rows = rows();
        assertEquals("Test_Hallowed", ReputationView.pick(rows, "test_hallowed").id());
        assertEquals("Test_Old_Jack", ReputationView.pick(rows, "Nobody").id());
        assertEquals("Test_Old_Jack", ReputationView.pick(rows, null).id());
        assertNull(ReputationView.pick(List.of(), "Test_Old_Jack"), "nothing met, nothing to show");
    }

    @Test
    void aRowReadsItsNameRankWhatComesNextAndItsGear() {
        engine.stored.put("Test_Old_Jack", 1_500);
        assertEquals("ziggfreedcommon.reputation.page.row.next", ReputationView.rowLine(rows().get(0)).getMessageId());
        engine.stats.put("Reputation_Test", 100L);
        assertEquals("ziggfreedcommon.reputation.page.row.gear", ReputationView.rowLine(rows().get(0)).getMessageId(),
                "gear wraps the line with how much it adds");
        engine.stats.put("Reputation_Test", -100L);
        assertEquals("ziggfreedcommon.reputation.page.row.gear.loss", ReputationView.rowLine(rows().get(0)).getMessageId());
        engine.stats.remove("Reputation_Test");
        engine.stored.put("Test_Hallowed", 25_000);
        assertEquals("ziggfreedcommon.reputation.page.row.reward", ReputationView.rowLine(rows().get(1)).getMessageId());
        engine.stored.put("Test_Old_Jack", 30_000);
        assertEquals("ziggfreedcommon.reputation.page.row", ReputationView.rowLine(rows().get(0)).getMessageId(),
                "at the top with no Beyond, the line is the name and the rank");
    }

    @Test
    void theDetailSaysTheDescriptionEarnedGearWhatComesNextAndTheCap() {
        engine.stored.put("Test_Old_Jack", 1_500);
        engine.stats.put("Reputation_Test", 100L);
        assertEquals(List.of("test.jack.desc", "ziggfreedcommon.reputation.detail.earned",
                        "ziggfreedcommon.reputation.detail.gear", "ziggfreedcommon.reputation.detail.next",
                        "ziggfreedcommon.reputation.detail.cap"),
                ids(ReputationView.detailLines(rows().get(0))));
        engine.stored.put("Test_Hallowed", 25_000);
        assertTrue(ids(ReputationView.detailLines(rows().get(1))).contains("ziggfreedcommon.reputation.detail.reward"));
        engine.stored.put("Test_Old_Jack", 30_000);
        engine.stats.remove("Reputation_Test");
        assertTrue(ids(ReputationView.detailLines(rows().get(0))).contains("ziggfreedcommon.reputation.detail.top"));
    }
}
