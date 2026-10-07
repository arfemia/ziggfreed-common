package com.ziggfreed.common.reputation.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.GatedContent;
import com.ziggfreed.common.reputation.FakeReputationNative;
import com.ziggfreed.common.reputation.ReputationDef;
import com.ziggfreed.common.reputation.ReputationFactors;
import com.ziggfreed.common.reputation.ReputationFanOut;
import com.ziggfreed.common.reputation.ReputationFixtures;
import com.ziggfreed.common.reputation.ReputationLadder;
import com.ziggfreed.common.reputation.ReputationService;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.Progress;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * What the page shows, decided where a test can reach it: one row per met reputation with the rank above it
 * or, past the top, the next Beyond reward; the pick; a row's meta, bar and colour; the reading page's blocks;
 * how to earn it; the ladder with what each rank opens; and which gated content a rank opens.
 */
class ReputationViewTest {

    private static final String PREFIX = "ziggfreedcommon.reputation.";

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
                          "Gear": { "Stat": "Reputation_Test" }, "Cap": 21000, "Order": 1,
                          "Earn": { "Lines": [ "test.jack.earn.quests", " ", "test.jack.earn.board" ] } }
                        """),
                "test_hallowed", ReputationFixtures.companion("Test_Hallowed", """
                        { "Order": 2,
                          "Beyond": { "Every": 5000, "Rewards": [ { "Kind": "Test_Kind", "Params": { "X": "Y" } } ] },
                          "Kills": [ { "NPCGroups": [ "Test_Mobs" ], "Amount": 2 } ] }
                        """)));
    }

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
    }

    private List<ReputationView.Row> rows() {
        return ReputationView.rows(service.met(null, null), service::ladderFor);
    }

    private ReputationLadder ladder() {
        return service.ladder();
    }

    /** A line's words as a test reads them: its key, or the plain text a raw message carries. */
    private static String word(DetailLine line) {
        Message text = line.text();
        return text.getMessageId() != null ? text.getMessageId() : String.valueOf(text.getRawText());
    }

    private static List<String> words(List<DetailLine> lines) {
        return lines.stream().map(ReputationViewTest::word).toList();
    }

    private static GatedContent.Entry gated(String name, GateSpec requires) {
        return new GatedContent.Entry(requires, "Test_Item", true, Msg.raw(name), Msg.raw("Test Stall"));
    }

    private static GateSpec spec(FactorCondition factor) {
        return GateSpec.of(new FactorCondition[] {factor}, null, null, null, null, null, null);
    }

    private static GateClause clause(FactorCondition factor) {
        return GateClause.of(new FactorCondition[] {factor}, null, null, null);
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
    void aRowsMetaSaysWhatComesNextAndWhatGearAdds() {
        engine.stored.put("Test_Old_Jack", 1_500);
        assertEquals(PREFIX + "row.next", ReputationView.rowMeta(rows().get(0)).getMessageId());
        engine.stats.put("Reputation_Test", 100L);
        assertEquals(PREFIX + "row.gear", ReputationView.rowMeta(rows().get(0)).getMessageId(),
                "gear wraps the line with how much it adds");
        engine.stats.put("Reputation_Test", -100L);
        assertEquals(PREFIX + "row.gear.loss", ReputationView.rowMeta(rows().get(0)).getMessageId());
        engine.stats.remove("Reputation_Test");
        engine.stored.put("Test_Hallowed", 25_000);
        assertEquals(PREFIX + "row.reward", ReputationView.rowMeta(rows().get(1)).getMessageId());
        engine.stored.put("Test_Old_Jack", 30_000);
        assertEquals(PREFIX + "row.top", ReputationView.rowMeta(rows().get(0)).getMessageId(),
                "at the top with no Beyond, the line says so rather than leaving a number unexplained");
    }

    @Test
    void theBarCountsThroughTheRankTowardTheNextOrTowardTheNextRewardAndIsFullWhereNothingIsLeft() {
        engine.stored.put("Test_Old_Jack", 1_500);
        engine.stored.put("Test_Hallowed", 25_000);
        List<ReputationView.Row> rows = rows();
        ReputationLadder.Rank friendly = ladder().byId("Friendly");
        ReputationLadder.Rank honored = ladder().byId("Honored");
        assertEquals(new Progress(1_500 - friendly.min(), honored.min() - friendly.min()),
                ReputationView.bar(rows.get(0)), "the way through the rank held, toward the next one");
        assertEquals(new Progress(25_000 - ladder().top().min(), 5_000), ReputationView.bar(rows.get(1)),
                "past the top, the way toward the next reward");
        engine.stats.put("Reputation_Test", 100L);
        assertEquals(1_600 - friendly.min(), ReputationView.bar(rows().get(0)).current(), "gear counts toward the rank");
        engine.stats.remove("Reputation_Test");
        engine.stored.put("Test_Old_Jack", 30_000);
        assertTrue(ReputationView.bar(rows().get(0)).complete(), "the top with no Beyond is a full bar");
    }

    @Test
    void aRankReadsInItsStandingsColour() {
        ReputationDef jack = service.known(ReputationFixtures.OLD_JACK);
        ReputationLadder ladder = ladder();
        assertEquals(Tone.DANGER, ReputationView.tone(ladder, jack, ladder.byId("Unfriendly")), "below the start");
        assertEquals(Tone.AVAILABLE, ReputationView.tone(ladder, jack, ladder.byId("Neutral")), "where it starts");
        assertEquals(Tone.DONE, ReputationView.tone(ladder, jack, ladder.byId("Honored")), "above the start");
        assertEquals(Tone.ACTIVE, ReputationView.tone(ladder, jack, ladder.top()), "the top");
        assertEquals(Tone.NEUTRAL, ReputationView.tone(ladder, jack, null), "no rank, no colour");
    }

    @Test
    void theListIsOneSectionWithARowPerReputationCarryingItsRankWordAndItsBar() {
        engine.stored.put("Test_Old_Jack", 1_500);
        engine.stored.put("Test_Hallowed", 10);
        LedgerModel model = ReputationView.ledger(rows(), ladder());
        assertEquals(1, model.sections().size());
        List<LedgerRow> rows = model.sections().get(0).rows();
        assertEquals(List.of("Test_Old_Jack", "Test_Hallowed"), rows.stream().map(LedgerRow::id).toList());
        assertEquals("Test_Old_Jack", model.firstSelectable());
        LedgerRow jack = rows.get(0);
        assertEquals(PREFIX + "rank.friendly", jack.state().getMessageId(), "the rank is the row's state word");
        assertEquals(Tone.DONE, jack.tone());
        assertNotNull(jack.progress(), "every row has a bar");
        assertNull(jack.value(), "no number stands alone at the row's end");
    }

    @Test
    void theReadingPageCarriesTheDescriptionWholeTheBarAndItsBlocksInOrder() {
        engine.stored.put("Test_Old_Jack", 1_500);
        DetailView page = ReputationView.detail(rows().get(0), ladder(), List.of());
        assertEquals("test.jack.desc", page.lead().getMessageId(), "the description is the page's lead paragraph");
        assertEquals(PREFIX + "detail.standing", page.progressLabel().getMessageId());
        assertNotNull(page.progress());
        assertEquals(List.of(ReputationView.BLOCK_EARN, ReputationView.BLOCK_RANKS, ReputationView.BLOCK_STANDING),
                page.blocks().stream().map(DetailBlock::id).toList(), "no Beyond block without a Beyond reward");
        assertEquals(PREFIX + "detail.rank", page.badges().get(0).label().getMessageId());
        assertEquals(PREFIX + "hint.gear", page.hint().getMessageId(), "gear that adds is explained at the foot");
        assertTrue(page.actions().isEmpty());
    }

    @Test
    void pastTheTopTheReadingPageSaysHowOftenItPaysAndWhereTheNextOneLands() {
        engine.stored.put("Test_Hallowed", 25_000);
        DetailView page = ReputationView.detail(ReputationView.pick(rows(), "Test_Hallowed"), ladder(), List.of());
        assertEquals(List.of(ReputationView.BLOCK_EARN, ReputationView.BLOCK_RANKS, ReputationView.BLOCK_BEYOND,
                ReputationView.BLOCK_STANDING), page.blocks().stream().map(DetailBlock::id).toList());
        DetailBlock beyond = page.blocks().get(2);
        assertEquals(PREFIX + "block.beyond", beyond.label().getMessageId());
        assertEquals(PREFIX + "block.beyond.meta", beyond.meta().getMessageId(), "how much each payout takes");
        assertEquals(PREFIX + "beyond.next", word(beyond.lines().get(beyond.lines().size() - 1)));
        assertEquals(PREFIX + "detail.standing.reward", page.progressLabel().getMessageId(),
                "the bar counts toward the next payout");
    }

    @Test
    void theStandingBlockSaysEarnedGearAndTheCap() {
        engine.stored.put("Test_Old_Jack", 1_500);
        engine.stats.put("Reputation_Test", 100L);
        assertEquals(List.of(PREFIX + "standing.earned", PREFIX + "standing.gear", PREFIX + "standing.cap"),
                words(ReputationView.standingLines(rows().get(0).standing())));
        engine.stored.put("Test_Hallowed", 10);
        assertEquals(List.of(PREFIX + "standing.earned"), words(ReputationView.standingLines(rows().get(1).standing())),
                "no gear and no cap, only what was earned");
    }

    @Test
    void howToEarnIsTheAuthorsOwnLinesElseWhatTheLibraryCanSee() {
        engine.stored.put("Test_Old_Jack", 10);
        engine.stored.put("Test_Hallowed", 10);
        List<ReputationView.Row> rows = rows();
        assertEquals(List.of("test.jack.earn.quests", "test.jack.earn.board"),
                words(ReputationView.earnLines(rows.get(0).reputation())), "authored lines, blanks dropped, in order");
        assertEquals(List.of(PREFIX + "earn.kills"), words(ReputationView.earnLines(rows.get(1).reputation())),
                "with no lines of its own, the kills that count");
        ReputationFixtures.loadCompanions(Map.of());
        assertEquals(List.of(PREFIX + "earn.default"),
                words(ReputationView.earnLines(service.known("Test_Hallowed"))), "with nothing at all, the plain fact");
    }

    @Test
    void theLadderRunsFromTheStartToTheTopWithTheHeldRankMarkedAndWhatEachRankOpens() {
        engine.stored.put("Test_Old_Jack", 1_500);
        ReputationLadder ladder = ladder();
        List<ReputationView.Unlock> unlocks = List.of(
                new ReputationView.Unlock(ladder.byId("Neutral"), gated("Lantern", GateSpec.OPEN)),
                new ReputationView.Unlock(ladder.byId("Revered"), gated("Helm", GateSpec.OPEN)));
        List<DetailLine> lines = ReputationView.rankLines(rows().get(0), ladder, unlocks);
        assertEquals(List.of(PREFIX + "rank.neutral", PREFIX + "unlock.at", PREFIX + "rank.friendly",
                        PREFIX + "rank.honored", PREFIX + "rank.revered", PREFIX + "unlock.at", PREFIX + "rank.exalted"),
                words(lines),
                "from where it starts (the ranks below it are left off) to the top, each rank's openings under it");
        assertEquals(Tick.DONE, lines.get(0).tick());
        DetailLine held = lines.get(2);
        assertEquals(Tick.CURRENT, held.tick());
        assertTrue(held.current(), "the held rank reads in bright ink");
        assertEquals(Tick.AHEAD, lines.get(3).tick());
        assertNotNull(lines.get(1).tag(), "an opening of a rank held is marked unlocked");
        assertTrue(lines.get(1).picture().tooltip(), "an item handed over keeps its own tooltip");
        assertNull(lines.get(5).tag(), "an opening of a rank ahead is not");
        engine.stored.put("Test_Old_Jack", -100);
        assertEquals(PREFIX + "rank.unfriendly", word(ReputationView.rankLines(rows().get(0), ladder, List.of()).get(0)),
                "a player standing below the start sees the ladder from where they stand");
    }

    @Test
    void aRankOpensWhatAsksForItOrForAStandingInItAtTheLowestRankAsked() {
        ReputationDef jack = service.known(ReputationFixtures.OLD_JACK);
        ReputationLadder ladder = ladder();
        GateSpec either = GateSpec.of(null, null, null, null, null, new GateClause[] {
                clause(FactorCondition.of(ReputationFactors.RANK, "test_old_jack/Exalted", 1.0, null)),
                clause(FactorCondition.of(ReputationFactors.RANK, "Test_Old_Jack/revered", 1.0, null))}, null);
        GateSpec standing = spec(FactorCondition.of(ReputationFactors.STANDING, "Test_Old_Jack", 4_000.0, null));
        GateSpec other = spec(FactorCondition.of(ReputationFactors.RANK, "Test_Hallowed/Friendly", 1.0, null));
        GateSpec below = spec(FactorCondition.of(ReputationFactors.RANK, "Test_Old_Jack/Friendly", null, 0.0));
        GateSpec negated = GateSpec.of(null, null, null, null, null, null, new GateClause[] {
                clause(FactorCondition.of(ReputationFactors.RANK, "Test_Old_Jack/Friendly", 1.0, null))});
        GateSpec plain = spec(FactorCondition.of(ReputationFactors.RANK, "Test_Old_Jack/Honored", 1.0, null));
        List<ReputationView.Unlock> unlocks = ReputationView.unlocks(jack, ladder, List.of(gated("either", either),
                gated("standing", standing), gated("other", other), gated("below", below), gated("negated", negated),
                gated("plain", plain)));
        assertEquals(List.of("either", "standing", "plain"),
                unlocks.stream().map(u -> u.entry().name().getRawText()).toList(),
                "another reputation's gate, a gate on staying below a rank and a Not group open nothing here");
        assertEquals("Revered", unlocks.get(0).rank().id(), "either route opens it, so it sits at the lower rank");
        assertEquals("Honored", unlocks.get(1).rank().id(), "a standing sits in the rank it falls in");
        assertTrue(ReputationView.unlocks(jack, ReputationLadder.EMPTY, List.of(gated("x", plain))).isEmpty(),
                "no ladder, no ranks to open anything");
    }
}
