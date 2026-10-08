package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.feedback.moment.FeedbackMomentAsset;
import com.ziggfreed.common.progress.asset.ObjectiveKindAsset;
import com.ziggfreed.common.reputation.event.ReputationEvents;
import com.ziggfreed.common.reputation.event.ZigReputationChangedEvent;
import com.ziggfreed.common.reputation.event.ZigReputationRanksHeldEvent;
import com.ziggfreed.common.ui.hud.panel.HudBarReading;
import com.ziggfreed.common.ui.hud.panel.HudRowDisplay;

/**
 * What the production fan-out says: the change event with every fact, the ranks-held event a credit
 * raises, the bar's row, reading and look, which change goes to the centred panel instead (one made
 * while a page is open; one row per reputation, the World bar's reading, full only at the cap), the rank
 * notice's values, the Beyond payout held to its ceiling, and the two shipped files that go with them.
 */
class ReputationFanOutTest {

    private final List<IEvent<Void>> fired = new ArrayList<>();
    private FakeReputationNative engine;
    private ReputationFixtures.RecordingFanOut recorded;
    private ReputationService service;

    @BeforeEach
    void seed() {
        ReputationFixtures.reset();
        ReputationEvents.SEAM.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                fired.add(build.get());
            }
        });
        engine = ReputationFixtures.engine();
        recorded = new ReputationFixtures.RecordingFanOut();
        service = new ReputationService(engine, recorded);
        ReputationFixtures.loadCompanions(Map.of("test_old_jack", ReputationFixtures.companion(
                ReputationFixtures.OLD_JACK,
                "{ \"Text\": { \"TitleKey\": \"test.jack.name\" }, \"Icon\": \"Test_Icon\", \"Order\": 2 }")));
    }

    @AfterEach
    void clear() {
        ReputationEvents.SEAM.publishTo(null);
        ReputationFixtures.reset();
    }

    private ReputationChange change(int before, int delta) {
        engine.stored.put("Test_Old_Jack", before);
        service.change(null, null, "Test_Old_Jack", delta, "quest:test");
        return recorded.changes.get(recorded.changes.size() - 1);
    }

    @Test
    void theChangeEventCarriesEveryFactOfTheChange() {
        ReputationChange up = change(500, 2_600);
        new EngineReputationFanOut().changed(null, null, up);
        assertEquals(1, fired.size(), "with no player handles only the event goes out");
        ZigReputationChangedEvent event = assertInstanceOf(ZigReputationChangedEvent.class, fired.get(0));
        assertEquals(engine.player, event.playerId());
        assertEquals("Test_Old_Jack", event.reputationId());
        assertEquals(500, event.earnedBefore());
        assertEquals(3_100, event.earnedAfter());
        assertEquals(3_100L, event.effectiveAfter());
        assertEquals("Neutral", event.rankBefore());
        assertEquals("Honored", event.rankAfter());
        assertEquals("quest:test", event.source());
    }

    @Test
    void aCreditRaisesTheRanksHeldEvent() {
        ReputationLadder ladder = ReputationLadder.of(ReputationFixtures.LADDER);
        new EngineReputationFanOut().credit(null, null, engine.player, service.known("Test_Old_Jack"),
                ladder.credited(ladder.byId("Friendly"), ladder.byId("Neutral")));
        ZigReputationRanksHeldEvent event = assertInstanceOf(ZigReputationRanksHeldEvent.class, fired.get(0));
        assertEquals(engine.player, event.playerId());
        assertEquals("Test_Old_Jack", event.reputationId());
        assertEquals(List.of("Neutral", "Friendly"), event.ranks());
    }

    @Test
    void theBarIsTheReputationsOwnRowReadingItsRankProgress() {
        ReputationChange up = change(500, 1_000);
        assertEquals("reputation:Test_Old_Jack", EngineReputationFanOut.rowId(up));
        HudBarReading reading = EngineReputationFanOut.reading(up);
        assertEquals(500.0, reading.current(), "1,500 is 500 into Friendly");
        assertEquals(2_000.0, reading.maximum(), "which spans 2,000 to Honored");
        HudRowDisplay display = EngineReputationFanOut.display(up);
        assertEquals("ziggfreedcommon.reputation.hud.caption", display.label().getMessageId());
        assertEquals(Integer.valueOf(2), display.order());
        assertNotNull(display.icon());
    }

    @Test
    void onlyAChangeMadeWhileAPageIsOpenGoesToTheCentreAndEveryOtherToTheWorldBar() {
        ReputationChange up = change(500, 1_000);
        ReputationChange down = change(1_500, -200);

        assertTrue(EngineReputationFanOut.centred(up, true), "a gain made in a page waits in the centre");
        assertTrue(EngineReputationFanOut.centred(down, true), "and so does a loss");
        assertFalse(EngineReputationFanOut.centred(up, false), "out in the world it is the World bar's alone");
        assertFalse(EngineReputationFanOut.centred(down, false));

        HudBarReading reading = EngineReputationFanOut.toastReading(up);
        assertEquals(500.0, reading.current(), "the bar is the progress through the rank held: 500 into Friendly");
        assertEquals(2_000.0, reading.maximum(), "of the 2,000 to Honored");
        assertEquals(300.0, EngineReputationFanOut.toastReading(down).current(),
                "a loss shows the standing it left: 1,300 is 300 into Friendly");
    }

    @Test
    void gainsCloseTogetherMergeOnTheReputationsOneRowAndTwoReputationsKeepTheirOwn() {
        engine.group("Test_Traders", 0);
        ReputationChange first = change(500, 100);
        ReputationChange second = change(600, 25);
        service.change(null, null, "Test_Traders", 50, "quest:test");
        ReputationChange other = recorded.changes.get(recorded.changes.size() - 1);

        assertEquals(EngineReputationFanOut.rowId(first), EngineReputationFanOut.rowId(second),
                "one row per reputation, so its +N adds the two gains up while it is up");
        assertNotEquals(EngineReputationFanOut.rowId(first), EngineReputationFanOut.rowId(other),
                "a second reputation gets a row of its own");
    }

    @Test
    void atTheTopRankTheCentredBarCountsTowardTheNextBeyondPayoutAsTheWorldBarDoes() {
        ReputationFixtures.loadCompanions(Map.of("test_old_jack", ReputationFixtures.companion(ReputationFixtures.OLD_JACK,
                "{ \"Beyond\": { \"Every\": 5000, \"Rewards\": [ { \"Kind\": \"Item\", \"Params\": { \"Item\": \"Test_Cache\" } } ] } }")));
        ReputationChange top = change(22_000, 100);

        assertEquals(EngineReputationFanOut.reading(top), EngineReputationFanOut.toastReading(top),
                "the World bar's own reading, toward the next Beyond payout");
        assertEquals(1_100.0, EngineReputationFanOut.toastReading(top).current(), "22,100 is 1,100 past Exalted's floor");
        assertEquals(5_000.0, EngineReputationFanOut.toastReading(top).maximum(), "of the 5,000 to the next payout");
    }

    @Test
    void atTheCapTheCentredBarIsFull() {
        ReputationFixtures.loadCompanions(Map.of("test_old_jack", ReputationFixtures.companion(ReputationFixtures.OLD_JACK,
                "{ \"Cap\": 2000 }")));
        ReputationChange capped = change(1_500, 1_000);

        assertEquals(500, capped.delta(), "the cap cut the gain to what was left");
        assertEquals(2_000, capped.earnedAfter());
        assertEquals(1.0, EngineReputationFanOut.toastReading(capped).fraction(), 1e-9,
                "nothing more to earn reads full, mid-rank or not");
        assertEquals(0.5, EngineReputationFanOut.reading(capped).fraction(), 1e-9, "2,000 is halfway through Friendly");
    }

    @Test
    void theRankNoticeCarriesTheNameTheRankThePictureAndTheSource() {
        ReputationDef jack = service.known("Test_Old_Jack");
        Map<String, Object> args = EngineReputationFanOut.rankArgs(jack, new ReputationLadder.Rank("Friendly", 1_000, 3_000));
        assertEquals("Test_Old_Jack", args.get(FeedbackEngine.SOURCE_ARG));
        assertEquals("test.jack.name", ((Message) args.get("name")).getMessageId());
        assertEquals("ziggfreedcommon.reputation.rank.friendly", ((Message) args.get("rank")).getMessageId());
        assertEquals("Test_Icon", args.get("icon"));
    }

    @Test
    void pastTheTopEachCrossingPaysOnceAndAHugeGainIsHeldToTheCeiling() {
        ReputationFixtures.loadCompanions(Map.of("test_old_jack", ReputationFixtures.companion(ReputationFixtures.OLD_JACK,
                "{ \"Beyond\": { \"Every\": 5000, \"Rewards\": [ { \"Kind\": \"Item\", \"Params\": { \"Item\": \"Test_Cache\" } } ] } }")));
        assertEquals(3, EngineReputationFanOut.beyondPayout(change(25_000, 11_000)).size());
        assertTrue(EngineReputationFanOut.beyondPayout(change(500, 100)).isEmpty(), "below the top nothing pays");
        ReputationFixtures.loadCompanions(Map.of("test_old_jack", ReputationFixtures.companion(ReputationFixtures.OLD_JACK,
                "{ \"Beyond\": { \"Every\": 1, \"Rewards\": [ { \"Kind\": \"Item\", \"Params\": { \"Item\": \"Test_Cache\" } } ] } }")));
        assertEquals(EngineReputationFanOut.MAX_BEYOND_PAYOUTS,
                EngineReputationFanOut.beyondPayout(change(25_000, 11_000)).size());
    }

    @Test
    void theRankNoticeShipsAsAFileWhoseLinesAreAuthored() throws IOException {
        FeedbackMomentAsset moment = FeedbackMomentAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(resource("/Server/ZiggfreedCommon/FeedbackMoments/Reputation_Rank.json")),
                null, new AssetExtraInfo<>(new AssetExtraInfo.Data(FeedbackMomentAsset.class,
                        EngineReputationFanOut.RANK_MOMENT, null)));
        assertEquals("ziggfreedcommon.reputation.feedback.rank.title", moment.getToast().getTitle().getKey());
        assertEquals("rank", moment.getToast().getTitle().getArgs()[0]);
        assertEquals("name", moment.getToast().getSecondary().getArgs()[0]);
        assertNotNull(moment.getSound(), "a rise has its jingle");
    }

    @Test
    void theRankStepShipsAsAProducibleCountingKindWithBothSentences() throws IOException {
        ObjectiveKindAsset kind = ObjectiveKindAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromJsonString(resource("/Server/ZiggfreedCommon/ObjectiveKinds/Reputation_Rank.json")),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ObjectiveKindAsset.class, "Reputation_Rank", null)));
        assertEquals(Boolean.TRUE, kind.getProducible());
        assertEquals(Boolean.FALSE, kind.getValueBased());
        assertEquals("ziggfreedcommon.reputation.objective.reputation_rank", kind.getPresentation().getTextKey());
        assertTrue(ReputationLangTest.englishKeys().contains("objective.reputation_rank.any"),
                "a step naming no rank reads the .any twin");
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = ReputationFanOutTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "the module ships " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
