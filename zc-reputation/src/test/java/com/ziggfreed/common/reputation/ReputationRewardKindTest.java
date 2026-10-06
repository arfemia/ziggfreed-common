package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * The Reputation reward kind: what it reads, that a payout with nobody live fails loud with the engine's
 * own replayable line, that a reward that could never pay is refused and not replayable, that a capped
 * gain is no failure, and how the reward reads on a chip.
 */
class ReputationRewardKindTest {

    private static final Subject OFFLINE = Subject.of(UUID.randomUUID(), "Tester");

    private FakeReputationNative engine;
    private ReputationService service;

    @BeforeEach
    void seed() {
        ReputationFixtures.reset();
        engine = ReputationFixtures.engine();
        service = new ReputationService(engine, ReputationFanOut.NONE);
    }

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
    }

    private RewardHandler kind() {
        RewardKindRegistry kinds = new RewardKindRegistry();
        ReputationRewardKind.registerInto(kinds, service);
        RewardHandler handler = kinds.handler("reputation");
        assertNotNull(handler, "the kind id matches case-insensitively like every other");
        return handler;
    }

    private static RewardSpec spec(String id, Object amount) {
        return RewardSpec.of("Reputation", Map.of("Reputation", id, "Amount", String.valueOf(amount)));
    }

    @Test
    void theParamsAreReadInAnySpelling() {
        assertEquals("Test_Old_Jack", ReputationRewardKind.reputationOf(
                RewardSpec.of("Reputation", Map.of("reputation", " Test_Old_Jack "))));
        assertEquals(250, ReputationRewardKind.amountOf(spec("x", "250.0")), "a whole decimal reads as that number");
        assertEquals(-50, ReputationRewardKind.amountOf(spec("x", -50)));
        assertEquals(0, ReputationRewardKind.amountOf(RewardSpec.of("Reputation")));
    }

    @Test
    void noLivePlayerFailsLoudAndOffersTheEnginesOwnLine() {
        assertThrows(IllegalStateException.class, () -> kind().grant(spec("test_old_jack", 250), OFFLINE));
        assertEquals("reputation add Test_Old_Jack 250 --player=Tester",
                kind().retryCommand(spec("test_old_jack", 250), OFFLINE, "quest:test"),
                "the engine's own command, with the engine's own spelling of the id");
    }

    @Test
    void aRewardThatCouldNeverPayIsRefusedAndNotReplayable() {
        for (RewardSpec bad : List.of(RewardSpec.of("Reputation"), spec("Test_Old_Jack", 0), spec("Nobody", 50))) {
            assertThrows(IllegalStateException.class, () -> kind().grant(bad, OFFLINE), bad.toString());
            assertNull(kind().retryCommand(bad, OFFLINE, "quest:test"), bad.toString());
        }
    }

    @Test
    void aPaidRewardMovesStandingAndIsReceipted() {
        List<RewardSpec> receipt = new ArrayList<>();
        new ReputationRewardKind(service).pay(null, null, spec("Test_Old_Jack", 250), "quest:test", receipt::add);
        assertEquals(250, engine.stored.get("Test_Old_Jack"));
        assertEquals(1, receipt.size());
    }

    @Test
    void aGainCutToNothingByTheCapIsNoFailureAndNoReceipt() {
        ReputationFixtures.loadCompanions(Map.of("test_old_jack",
                ReputationFixtures.companion(ReputationFixtures.OLD_JACK, "{ \"Cap\": 100 }")));
        engine.stored.put("Test_Old_Jack", 100);
        List<RewardSpec> receipt = new ArrayList<>();
        new ReputationRewardKind(service).pay(null, null, spec("Test_Old_Jack", 250), "quest:test", receipt::add);
        assertTrue(receipt.isEmpty(), "nothing reached the player, so nothing is reported handed over");
        assertTrue(engine.writes.isEmpty());
    }

    @Test
    void aPayoutWithNobodyLiveThrowsForTheRetryQueue() {
        engine.live = false;
        assertThrows(IllegalStateException.class, () -> new ReputationRewardKind(service)
                .pay(null, null, spec("Test_Old_Jack", 250), "quest:test", ignored -> { }));
    }

    @Test
    void aRewardReadsAsItsAmountAndTheReputationsName() {
        ReputationFixtures.loadCompanions(Map.of("test_old_jack", ReputationFixtures.companion(
                ReputationFixtures.OLD_JACK, "{ \"Text\": { \"TitleKey\": \"test.jack.name\" }, \"Icon\": \"Test_Icon\" }")));
        RewardChip gain = ReputationRewardKind.chipFor(service, spec("test_old_jack", 250));
        assertEquals("ziggfreedcommon.reputation.reward.gain", gain.label().getMessageId());
        assertFalse(gain.hasIcon(), "a chip's picture reads as a promise of that item, so standing shows as its line alone");
        assertEquals("ziggfreedcommon.reputation.reward.loss",
                ReputationRewardKind.chipFor(service, spec("Test_Old_Jack", -50)).label().getMessageId());
        assertNull(ReputationRewardKind.chipFor(service, spec("Nobody", 5)), "an unknown reputation names nothing");
        assertNull(ReputationRewardKind.chipFor(service, RewardSpec.of("Item")), "another kind is not this reading's");
    }

    @Test
    void aQuestCardReadsTheRewardThroughTheContributedReading() {
        ReputationFixtures.loadCompanions(Map.of("test_old_jack", ReputationFixtures.companion(
                ReputationFixtures.OLD_JACK, "{ \"Text\": { \"TitleKey\": \"test.jack.name\" } }")));
        RewardChips.Source reading = ReputationRewardKind.chips(service);
        RewardChips.contribute(reading);
        try {
            List<RewardChip> chips = RewardChips.chipsFor(List.of(spec("Test_Old_Jack", 250)), null);
            assertEquals(1, chips.size(), "the generic reading cannot name the reward, so the contributed one does");
            assertEquals("ziggfreedcommon.reputation.reward.gain", chips.get(0).label().getMessageId());
        } finally {
            RewardChips.forget(reading);
        }
    }
}
