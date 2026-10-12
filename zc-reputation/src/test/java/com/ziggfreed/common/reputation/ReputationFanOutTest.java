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
 * raises, the bar's row, reading and look, that a change made while a page is open goes on no HUD panel
 * at all, the rank notice's values, and the two shipped files that go with them.
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
        assertEquals("test.jack.name", display.label().getMessageId(),
                "the reputation's name alone, so it reads whole in a World bars column (M571)");
        assertEquals(Integer.valueOf(2), display.order());
        assertNotNull(display.icon());
    }

    @Test
    void aChangeMadeWhileAPageIsOpenGoesOnNoHudPanel() {
        assertFalse(EngineReputationFanOut.movesBar(true),
                "under a page the action's own toast names the change: no World bar, no row held for the close (R290)");
        assertTrue(EngineReputationFanOut.movesBar(false), "out in the world it moves the World bar");
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
    void theRankNoticeCarriesTheNameTheRankThePictureAndTheSource() {
        ReputationDef jack = service.known("Test_Old_Jack");
        Map<String, Object> args = EngineReputationFanOut.rankArgs(jack, new ReputationLadder.Rank("Friendly", 1_000, 3_000));
        assertEquals("Test_Old_Jack", args.get(FeedbackEngine.SOURCE_ARG));
        assertEquals("test.jack.name", ((Message) args.get("name")).getMessageId());
        assertEquals("ziggfreedcommon.reputation.rank.friendly", ((Message) args.get("rank")).getMessageId());
        assertEquals("Test_Icon", args.get("icon"));
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
