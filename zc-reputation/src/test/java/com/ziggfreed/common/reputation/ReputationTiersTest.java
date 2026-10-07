package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.reputation.asset.ReputationAsset;
import com.ziggfreed.common.reputation.event.ReputationEvents;
import com.ziggfreed.common.reputation.event.ZigReputationRanksHeldEvent;
import com.ziggfreed.common.reputation.page.ReputationView;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A reputation's own ranks above Exalted: they stand on its ladder alone, every reading reads them (the
 * factor, the rank credit a REPUTATION_RANK step counts, the bar, the page), Beyond starts past the top
 * tier, and a floor that would move a shared rank is an ERROR and ignored.
 */
class ReputationTiersTest {

    private static final String FESTIVAL = "Test_Festival";
    private static final String TIERS = """
            { "Text": { "TitleKey": "test.festival.name" },
              "Ranks": { "Exalted": { "Name": "test.festival.rank.exalted" },
                         "Test_Wayfarer": { "Name": "test.festival.rank.wayfarer", "From": 60000 },
                         "Test_Luminary": { "Name": "test.festival.rank.luminary", "From": 150000 } },
              "Beyond": { "Every": 30000 } }
            """;

    private FakeReputationNative engine;
    private ReputationService service;

    @BeforeEach
    void seed() {
        ReputationFixtures.reset();
        engine = ReputationFixtures.engine().group(FESTIVAL, 0);
        service = new ReputationService(engine, new ReputationFixtures.RecordingFanOut());
        ReputationFixtures.loadCompanions(Map.of("test_festival", ReputationFixtures.companion(FESTIVAL, TIERS)));
    }

    @AfterEach
    void clear() {
        ReputationEvents.SEAM.publishTo(null);
        ReputationFixtures.reset();
    }

    private static FactorContext ask(String param) {
        return FactorContext.builder().param(param).build();
    }

    private static List<String> ids(ReputationLadder ladder) {
        return ladder.ranks().stream().map(ReputationLadder.Rank::id).toList();
    }

    private ReputationView.Row row() {
        return ReputationView.rows(service.met(null, null), service::ladderFor).get(0);
    }

    @Test
    void twoTiersStandAboveTheSharedTopOnThisReputationsLadderAlone() {
        assertEquals(List.of("Hated", "Unfriendly", "Neutral", "Friendly", "Honored", "Revered", "Exalted",
                "Test_Wayfarer", "Test_Luminary"), ids(service.ladderOf("test_festival")));
        assertEquals(ReputationLadder.of(ReputationFixtures.LADDER).ranks(),
                service.ladderOf(ReputationFixtures.OLD_JACK).ranks(),
                "another reputation reads the shared ladder untouched");
        assertEquals(ReputationLadder.of(ReputationFixtures.LADDER).ranks(), service.ladder().ranks(),
                "and the server's ladder is still the shared seven");
    }

    @Test
    void standingFactorsAndThePageReadTheTiers() {
        engine.stored.put(FESTIVAL, 59_999);
        assertEquals("Exalted", service.standing(null, null, FESTIVAL).rank().id());
        assertEquals(0.0, ReputationFactors.rank(service, ask(FESTIVAL + "/Test_Wayfarer")));
        assertEquals("Test_Wayfarer", row().next().id(), "an Exalted player sees the first tier above");

        engine.stored.put(FESTIVAL, 60_000);
        assertEquals("Test_Wayfarer", service.standing(null, null, FESTIVAL).rank().id());
        assertEquals(1.0, ReputationFactors.rank(service, ask(FESTIVAL + "/test_wayfarer")), "a tier in any case");
        assertEquals(1.0, ReputationFactors.rank(service, ask(FESTIVAL + "/Exalted")), "a lower rank also holds");
        assertNull(ReputationFactors.rank(service, ask(ReputationFixtures.OLD_JACK + "/Test_Wayfarer")),
                "a tier is this reputation's alone");

        engine.stored.put(FESTIVAL, 200_000);
        assertEquals("Test_Luminary", service.standing(null, null, FESTIVAL).rank().id());
    }

    @Test
    void beyondStartsAfterTheTopTier() {
        engine.stored.put(FESTIVAL, 100_000);
        ReputationChange inside = service.change(null, null, FESTIVAL, 30_000, "t").change();
        assertEquals(0, inside.beyondCrossings(),
                "a gain between two tiers pays no Beyond, though it is far past Exalted's floor");
        assertEquals(new ReputationLadder.Progress(70_000, 90_000), inside.progress(),
                "the bar runs from the tier's floor to the next tier's");

        engine.stored.put(FESTIVAL, 160_000);
        ReputationChange past = service.change(null, null, FESTIVAL, 50_000, "t").change();
        assertEquals(2, past.beyondCrossings(), "past the top tier each new multiple of Every pays: 180,000, 210,000");
        assertEquals(new ReputationLadder.Progress(0, 30_000), past.progress());
        assertNull(row().next(), "nothing above the top tier");
        assertEquals(Long.valueOf(240_000), row().nextReward(), "the next reward sits one Every past the last");
    }

    @Test
    void aRankStepNamingATierIsCreditedWhenTheTierIsReached() {
        List<ZigReputationRanksHeldEvent> held = new ArrayList<>();
        ReputationEvents.SEAM.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                if (build.get() instanceof ZigReputationRanksHeldEvent ranks) {
                    held.add(ranks);
                }
            }
        });
        ReputationService live = new ReputationService(engine, new EngineReputationFanOut());
        engine.stored.put(FESTIVAL, 59_000);

        live.change(null, null, FESTIVAL, 2_000, "t");

        assertEquals(1, held.size());
        assertEquals(FESTIVAL, held.get(0).reputationId());
        assertEquals(List.of("Neutral", "Friendly", "Honored", "Revered", "Exalted", "Test_Wayfarer"),
                held.get(0).ranks(), "zc-objectives turns each into REPUTATION_RANK, Target the rank id, so a "
                        + "step with Target Test_Wayfarer and Qualifier Test_Festival counts from this credit");
    }

    @Test
    void aFloorThatWouldMoveASharedRankIsAnErrorAndIgnored() {
        ReputationAsset odd = ReputationFixtures.companion(FESTIVAL, """
                { "Ranks": { "Exalted": { "From": 50000 }, "Test_Low": { "From": 21000 },
                             "Test_Wayfarer": { "Name": "test.festival.rank.wayfarer", "From": 60000 },
                             "Test_Zeal": { "From": 60000 } } }
                """);
        ReputationFixtures.loadCompanions(Map.of("test_festival", odd));

        ReputationLadder festival = service.ladderOf(FESTIVAL);
        assertEquals(21_000, festival.byId("Exalted").min(), "Exalted's floor is every reputation's (M241)");
        assertNull(festival.byId("Test_Low"));
        assertNull(festival.byId("Test_Zeal"));
        assertEquals("Test_Wayfarer", festival.top().id());

        List<Finding> findings = ReputationValidator.audit(List.of(odd), engine.groups(), engine.ranks(),
                group -> true, stat -> true, item -> true);
        assertEquals(List.of(ReputationValidator.TIER_OUT_OF_RANGE, ReputationValidator.FROM_ON_SHARED_RANK,
                ReputationValidator.TIER_SHARES_FLOOR), findings.stream().map(Finding::code).toList());
        for (Finding finding : findings) {
            assertEquals(Severity.ERROR, finding.severity(), finding.code());
        }
    }

    @Test
    void theAuditAcceptsAReputationsOwnTiersAndWarnsWhenItsCapLeavesOneOutOfReach() {
        assertEquals(List.of(), ReputationValidator.audit(List.of(ReputationFixtures.companion(FESTIVAL, TIERS)),
                engine.groups(), engine.ranks(), group -> true, stat -> true, item -> true),
                "a name for its own tier is no UNKNOWN_RANK");
        List<Finding> capped = ReputationValidator.audit(List.of(ReputationFixtures.companion(FESTIVAL, """
                        { "Cap": 100000,
                          "Ranks": { "Test_Wayfarer": { "Name": "test.festival.rank.wayfarer", "From": 60000 },
                                     "Test_Luminary": { "Name": "test.festival.rank.luminary", "From": 150000 } } }
                        """)), engine.groups(), engine.ranks(), group -> true, stat -> true, item -> true);
        assertEquals(List.of(ReputationValidator.TIER_ABOVE_CAP), capped.stream().map(Finding::code).toList());
        assertEquals(Severity.WARNING, capped.get(0).severity());
        assertTrue(capped.get(0).message().contains("Test_Luminary"), capped.get(0).message());
    }

    @Test
    void aTierOnTheLadderWithNoNameIsAWarning() {
        List<Finding> findings = ReputationValidator.audit(List.of(ReputationFixtures.companion(FESTIVAL, """
                        { "Ranks": { "Test_Wayfarer": { "Name": "test.festival.rank.wayfarer", "From": 60000 },
                                     "Test_Luminary": { "From": 150000 }, "Test_Low": { "From": 21000 },
                                     "Exalted": { "From": 50000 } } }
                        """)), engine.groups(), engine.ranks(), group -> true, stat -> true, item -> true);
        assertEquals(List.of(ReputationValidator.TIER_OUT_OF_RANGE, ReputationValidator.FROM_ON_SHARED_RANK,
                        ReputationValidator.TIER_WITHOUT_NAME), findings.stream().map(Finding::code).toList(),
                "only a tier that stands on the ladder needs a name: a refused one already has its ERROR, and a "
                        + "shared rank reads with the library's word");
        assertEquals(Severity.WARNING, findings.get(2).severity());
        assertTrue(findings.get(2).message().contains("Test_Luminary"), findings.get(2).message());
    }

    @Test
    void aTierWrittenTwiceInTwoCasesIsAnErrorAndTheSecondByFloorIsIgnored() {
        ReputationAsset twice = ReputationFixtures.companion(FESTIVAL, """
                { "Ranks": { "test_wayfarer": { "Name": "test.festival.rank.wayfarer.late", "From": 90000 },
                             "Test_Wayfarer": { "Name": "test.festival.rank.wayfarer", "From": 60000 } } }
                """);
        ReputationFixtures.loadCompanions(Map.of("test_festival", twice));

        assertEquals(List.of("Hated", "Unfriendly", "Neutral", "Friendly", "Honored", "Revered", "Exalted",
                "Test_Wayfarer"), ids(service.ladderOf(FESTIVAL)), "ids match without regard to case");

        List<Finding> findings = ReputationValidator.audit(List.of(twice), engine.groups(), engine.ranks(),
                group -> true, stat -> true, item -> true);
        assertEquals(List.of(ReputationValidator.TIER_SHARES_ID), findings.stream().map(Finding::code).toList());
        assertEquals(Severity.ERROR, findings.get(0).severity());
        assertTrue(findings.get(0).message().contains("'test_wayfarer'"), findings.get(0).message());
    }

    @Test
    void aGateOnATierNamesTheRankAndTheReputationWithNoOverlay() {
        ReputationFactors.contribute(service);

        Message line = FactorNames.name(ReputationFactors.RANK, FESTIVAL + "/Test_Wayfarer");

        assertNotNull(line, "no Factors file names this gate, and none has to");
        assertEquals(ReputationText.PREFIX + "factor.rank_with", line.getMessageId());
        assertEquals("test.festival.rank.wayfarer", line.getFormattedMessage().messageParams.get("0").messageId,
                "{0} is the rank's own name with this reputation");
        assertEquals("test.festival.name", line.getFormattedMessage().messageParams.get("1").messageId,
                "{1} is the reputation's name");
        assertEquals(ReputationText.PREFIX + "rank.honored", FactorNames.name(ReputationFactors.RANK,
                        FESTIVAL + "/Honored").getFormattedMessage().messageParams.get("0").messageId,
                "a shared rank it does not rename reads with the library's word");
    }

    @Test
    void aReputationThatNamesItselfNowhereKeepsTheGenericLine() {
        assertNull(ReputationFactors.rankLockName(service, ReputationFixtures.OLD_JACK + "/Friendly"),
                "no TitleKey, so a raw id never reaches a lock line");
        assertNull(ReputationFactors.rankLockName(service, FESTIVAL + "/Test_Nowhere"), "a rank not on its ladder");
        assertNull(ReputationFactors.rankLockName(service, "Nobody/Friendly"));
        assertNull(ReputationFactors.rankLockName(service, FESTIVAL));
    }
}
