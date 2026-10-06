package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.reputation.ReputationRankWatch.Trigger;
import com.ziggfreed.common.reputation.ReputationService.Standing;
import com.ziggfreed.common.reputation.ReputationService.Status;
import com.ziggfreed.common.reputation.asset.ReputationConfig;

/**
 * The service over a stand-in engine: the engine's own spelling of an id, the starting value, effective
 * standing, the cap and the clamp, every refusal, the Beyond count, the fan-out, and the effective-rank
 * checks on change, equip and login.
 */
class ReputationServiceTest {

    private FakeReputationNative engine;
    private ReputationFixtures.RecordingFanOut fanOut;
    private ReputationService service;

    @BeforeEach
    void seed() {
        ReputationFixtures.reset();
        engine = ReputationFixtures.engine();
        fanOut = new ReputationFixtures.RecordingFanOut();
        service = new ReputationService(engine, fanOut);
    }

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
    }

    private static void companion(String json) {
        ReputationFixtures.loadCompanions(Map.of("test_old_jack",
                ReputationFixtures.companion(ReputationFixtures.OLD_JACK, json)));
    }

    @Test
    void anIdInAnyCaseReachesTheEnginesOwnSpelling() {
        ReputationService.Result result = service.change(null, null, "test_old_jack", 250, "quest:test");
        assertEquals(Status.CHANGED, result.status());
        assertEquals(List.of("Test_Old_Jack 250"), engine.writes,
                "the engine's group map is keyed exactly, so zc writes the engine's own id");
        assertEquals("Test_Old_Jack", result.change().reputation().id());
        assertEquals(250, service.standing(null, null, "TEST_OLD_JACK").earned());
    }

    @Test
    void anUntouchedReputationReadsItsStartingValueAndIsNotMetAtZero() {
        engine.group("Test_Welcome", 500);
        Standing jack = service.standing(null, null, "Test_Old_Jack");
        assertEquals(0, jack.earned());
        assertFalse(jack.met(), "never moved and standing 0");
        Standing welcome = service.standing(null, null, "test_welcome");
        assertEquals(500, welcome.earned(), "an untouched reputation reads its InitialReputationValue");
        assertTrue(welcome.met(), "a standing other than 0 counts as met");
        assertEquals("Neutral", welcome.rank().id());
    }

    @Test
    void effectiveStandingAddsWhatGearGives() {
        companion("{ \"Gear\": { \"Stat\": \"Reputation_Test\" } }");
        engine.stats.put("Reputation_Test", 600L);
        engine.stored.put("Test_Old_Jack", 500);
        Standing standing = service.standing(null, null, "Test_Old_Jack");
        assertEquals(500, standing.earned());
        assertEquals(600, standing.gear());
        assertEquals(1_100, standing.effective());
        assertEquals("Friendly", standing.rank().id(), "the rank shown reads effective standing");
    }

    @Test
    void aGainStopsAtTheCapAndALossIsNeverCut() {
        companion("{ \"Cap\": 1000 }");
        engine.stored.put("Test_Old_Jack", 900);
        assertEquals(1_000, service.change(null, null, "Test_Old_Jack", 500, "t").change().earnedAfter());
        assertEquals(Status.UNCHANGED, service.change(null, null, "Test_Old_Jack", 50, "t").status(),
                "at the cap a gain writes nothing");
        assertEquals(List.of("Test_Old_Jack 100"), engine.writes, "the gain was cut to the cap before the write");
        assertEquals(800, service.change(null, null, "Test_Old_Jack", -200, "t").change().earnedAfter());
        engine.stored.put("Test_Old_Jack", 5_000);
        assertEquals(Status.UNCHANGED, service.change(null, null, "Test_Old_Jack", 10, "t").status(),
                "a player an admin set above the cap gains nothing more from zc");
    }

    @Test
    void theEnginesClampAtTheTopChangesNothingAndTellsNothing() {
        engine.stored.put("Test_Old_Jack", 1_999_999_999);
        assertEquals(Status.UNCHANGED, service.change(null, null, "Test_Old_Jack", 10, "t").status());
        assertTrue(fanOut.changes.isEmpty());
    }

    @Test
    void everyRefusalSaysWhyAndWritesNothing() {
        assertEquals(Status.UNKNOWN, service.change(null, null, "Nobody", 10, "t").status());
        assertEquals(Status.UNKNOWN, service.change(null, null, null, 10, "t").status());
        assertEquals(Status.ZERO, service.change(null, null, "Test_Old_Jack", 0, "t").status());
        engine.live = false;
        assertEquals(Status.NOT_LIVE, service.change(null, null, "Test_Old_Jack", 10, "t").status());
        assertNull(service.standing(null, null, "Test_Old_Jack"), "no live player, no reading");
        engine.live = true;
        companion("{ \"Enabled\": false }");
        assertEquals(Status.UNKNOWN, service.change(null, null, "Test_Old_Jack", 10, "t").status(),
                "a switched-off reputation is absent");
        ReputationFixtures.reset();
        ReputationConfig.getInstance().setGlobalEnabled(false);
        assertEquals(Status.UNKNOWN, service.change(null, null, "Test_Old_Jack", 10, "t").status(),
                "the owner's switch makes every reputation absent");
        ReputationConfig.getInstance().setGlobalEnabled(true);
        engine.available = false;
        assertEquals(Status.UNKNOWN, service.change(null, null, "Test_Old_Jack", 10, "t").status(),
                "no engine reputation plugin, no reputations");
        assertTrue(service.all().isEmpty());
        assertTrue(engine.writes.isEmpty());
        assertTrue(fanOut.changes.isEmpty());
    }

    @Test
    void pastTheTopEachNewMultipleOfEveryCountsOnce() {
        companion("{ \"Beyond\": { \"Every\": 5000 } }");
        engine.stored.put("Test_Old_Jack", 25_000);
        assertEquals(3, service.change(null, null, "Test_Old_Jack", 11_000, "t").change().beyondCrossings());
        assertEquals(0, service.change(null, null, "Test_Old_Jack", 1_000, "t").change().beyondCrossings());
    }

    @Test
    void theFanOutHearsEachChangeOnceAndAFailureThereNeverUndoesTheWrite() {
        service.change(null, null, "Test_Old_Jack", 10, "quest:test");
        assertEquals(1, fanOut.changes.size());
        assertEquals("quest:test", fanOut.changes.get(0).source());
        assertEquals(10, fanOut.changes.get(0).delta());
        ReputationService failing = new ReputationService(engine, new ReputationFixtures.RecordingFanOut() {
            @Override
            public void changed(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
                    @Nonnull ReputationChange change) {
                throw new IllegalStateException("boom");
            }
        });
        assertEquals(Status.CHANGED, failing.change(null, null, "Test_Old_Jack", 10, "t").status());
        assertEquals(20, engine.stored.get("Test_Old_Jack"));
    }

    @Test
    void metListsOnlyWhatThePlayerHasMetInPageOrder() {
        engine.group("Test_Alpha", 0).group("Test_Beta", 0);
        engine.stored.put("Test_Beta", 10);
        engine.stored.put("Test_Old_Jack", 0);
        ReputationFixtures.loadCompanions(Map.of("test_beta",
                ReputationFixtures.companion("Test_Beta", "{ \"Order\": -1 }")));
        assertEquals(List.of("Test_Beta", "Test_Old_Jack"),
                service.met(null, null).stream().map(s -> s.reputation().id()).toList(),
                "Order first, then id; an entry at 0 counts as met, an untouched 0 does not");
        assertTrue(service.anyMet(null, null));
        engine.live = false;
        assertTrue(service.met(null, null).isEmpty());
    }

    @Test
    void aBigGrantCreditsEveryRankHeldAndAnnouncesTheNewRankOnce() {
        engine.stored.put("Test_Old_Jack", 500);
        service.checkAll(null, null, Trigger.LOGIN);
        assertEquals(List.of("Test_Old_Jack: Neutral"), fanOut.credits,
                "login credits what the player already holds, from the starting rank up");
        assertTrue(fanOut.rises.isEmpty(), "and says nothing: the first check after login is a hydrate");
        fanOut.credits.clear();
        ReputationChange up = service.change(null, null, "Test_Old_Jack", 9_000, "t").change();
        assertEquals("Neutral", up.rankBefore().id());
        assertEquals("Revered", up.rankAfter().id());
        assertEquals(List.of("Test_Old_Jack: Neutral, Friendly, Honored, Revered"), fanOut.credits,
                "a jump credits every rank it passed, so an achievement on Honored is never missed");
        assertEquals(List.of("Test_Old_Jack: Revered"), fanOut.rises, "one notice, for the rank it landed on");
    }

    @Test
    void aNeutralOrAbovePlayerIsNeverCreditedHatedOrUnfriendly() {
        engine.stored.put("Test_Old_Jack", 4_000);
        service.checkAll(null, null, Trigger.LOGIN);
        assertEquals(List.of("Test_Old_Jack: Neutral, Friendly, Honored"), fanOut.credits,
                "credit runs from the reputation's starting rank (Neutral, where its start of 0 lands) up");
    }

    @Test
    void aPlayerWhoFallsToUnfriendlyIsCreditedUnfriendlyOnly() {
        service.checkAll(null, null, Trigger.LOGIN);
        fanOut.credits.clear();
        service.change(null, null, "Test_Old_Jack", -500, "t");
        assertEquals(List.of("Test_Old_Jack: Unfriendly"), fanOut.credits,
                "below the start only the ranks reached going down are credited, never the start's own");
        assertTrue(fanOut.rises.isEmpty(), "a fall is never announced");
        fanOut.credits.clear();
        service.change(null, null, "Test_Old_Jack", -5_000, "t");
        assertEquals(List.of("Test_Old_Jack: Hated, Unfriendly"), fanOut.credits);
    }

    @Test
    void aReputationStartingHigherCreditsFromItsOwnStartingRank() {
        engine.group("Test_Welcome", 1_500);
        engine.stored.put("Test_Welcome", 3_200);
        service.checkAll(null, null, Trigger.LOGIN);
        assertTrue(fanOut.credits.contains("Test_Welcome: Friendly, Honored"),
                "a reputation that starts in Friendly never credits Neutral: " + fanOut.credits);
    }

    @Test
    void aFallAnnouncesNothing() {
        engine.stored.put("Test_Old_Jack", 3_100);
        service.checkAll(null, null, Trigger.LOGIN);
        ReputationChange down = service.change(null, null, "Test_Old_Jack", -2_600, "t").change();
        assertEquals("Honored", down.rankBefore().id());
        assertEquals("Neutral", down.rankAfter().id());
        assertTrue(fanOut.rises.isEmpty());
        assertEquals(1, fanOut.changes.size(), "the change itself is still told: the event and the bar");
    }

    @Test
    void gearAloneRaisingTheRankCreditsItAndAnnouncesItOnce() {
        companion("{ \"Gear\": { \"Stat\": \"Reputation_Test\" } }");
        engine.stored.put("Test_Old_Jack", 900);
        service.checkAll(null, null, Trigger.LOGIN);
        fanOut.credits.clear();
        engine.stats.put("Reputation_Test", 200L);
        service.checkAll(null, null, Trigger.EQUIP);
        assertEquals(List.of("Test_Old_Jack: Neutral, Friendly"), fanOut.credits);
        assertEquals(List.of("Test_Old_Jack: Friendly"), fanOut.rises);
        service.checkAll(null, null, Trigger.EQUIP);
        assertEquals(1, fanOut.credits.size(), "an equip that moves no rank credits nothing again");
        assertEquals(1, fanOut.rises.size(), "and announces nothing again");
        assertTrue(engine.writes.isEmpty(), "gear never writes earned standing");
        assertTrue(fanOut.changes.isEmpty(), "and raises no change event");
    }

    @Test
    void loggingInWithGearAlreadyOnCreditsSilently() {
        companion("{ \"Gear\": { \"Stat\": \"Reputation_Test\" } }");
        engine.stored.put("Test_Old_Jack", 900);
        engine.stats.put("Reputation_Test", 200L);
        service.checkAll(null, null, Trigger.LOGIN);
        assertEquals(List.of("Test_Old_Jack: Neutral, Friendly"), fanOut.credits);
        assertTrue(fanOut.rises.isEmpty());
    }

    @Test
    void aPlayerWhoLeftStartsOverWithASilentHydrate() {
        engine.stored.put("Test_Old_Jack", 500);
        service.checkAll(null, null, Trigger.LOGIN);
        service.forget(engine.player);
        engine.stored.put("Test_Old_Jack", 1_500);
        service.checkAll(null, null, Trigger.EQUIP);
        assertTrue(fanOut.rises.isEmpty(), "with nothing remembered, the first check is a hydrate again");
        assertEquals(2, fanOut.credits.size());
        assertTrue(fanOut.credits.get(1).endsWith("Friendly"));
    }

    @Test
    void aCheckWithNoLivePlayerOrWithTheModuleOffDoesNothing() {
        engine.live = false;
        service.checkAll(null, null, Trigger.LOGIN);
        engine.live = true;
        ReputationConfig.getInstance().setGlobalEnabled(false);
        service.checkAll(null, null, Trigger.LOGIN);
        assertTrue(fanOut.credits.isEmpty());
        assertTrue(fanOut.rises.isEmpty());
    }
}
