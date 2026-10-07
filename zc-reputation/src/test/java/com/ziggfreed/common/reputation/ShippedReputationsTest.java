package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.DerivedFactorConfig;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.reputation.asset.ReputationAsset;
import com.ziggfreed.common.reputation.page.ReputationView;

/**
 * Hallow's Eve's two reputations, read from copies of the pack's own files, author no tier, so they read
 * exactly as before ranks above Exalted existed: the same ladder, ranks, factor readings, bars, Beyond
 * payouts, page rows and audit.
 */
class ShippedReputationsTest {

    private static final String JACK = "Hallows_Eve_Old_Jack";
    private static final String HALLOWED = "Hallows_Eve_Hallowed";

    private FakeReputationNative engine;
    private ReputationService service;
    private ReputationAsset jack;
    private ReputationAsset hallowed;

    @BeforeEach
    void seed() throws IOException {
        ReputationFixtures.reset();
        engine = new FakeReputationNative().group(JACK, 0).group(HALLOWED, 0);
        service = new ReputationService(engine, new ReputationFixtures.RecordingFanOut());
        jack = ReputationFixtures.companion(JACK, fixture("Reputations/Hallows_Eve_Old_Jack.json"));
        hallowed = ReputationFixtures.companion(HALLOWED, fixture("Reputations/Hallows_Eve_Hallowed.json"));
        ReputationFixtures.loadCompanions(Map.of("hallows_eve_old_jack", jack, "hallows_eve_hallowed", hallowed));
    }

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of());
        LangCatalog.overrideForTests(null);
    }

    static String fixture(String path) throws IOException {
        try (InputStream in = ShippedReputationsTest.class.getResourceAsStream("/fixtures/hallows-eve/" + path)) {
            assertNotNull(in, "a copy of the pack's file at " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void neitherAuthorsATierSoEachReadsTheSharedLadder() {
        List<ReputationLadder.Rank> shared = ReputationLadder.of(engine.ranks()).ranks();
        assertEquals(Map.of(), jack.tierFloors());
        assertEquals(Map.of(), hallowed.tierFloors());
        assertEquals(shared, service.ladderOf(JACK).ranks(), "the same ranks, floors and ceilings");
        assertEquals(shared, service.ladderOf(HALLOWED).ranks());
    }

    @Test
    void ranksAndFactorsReadAsTheSharedLadderDoes() {
        ReputationLadder shared = ReputationLadder.of(engine.ranks());
        for (int value : new int[] {-30_000, -1, 0, 999, 1_000, 8_999, 9_000, 21_000, 250_000}) {
            engine.stored.put(JACK, value);
            engine.stored.put(HALLOWED, value);
            for (String id : List.of(JACK, HALLOWED)) {
                assertEquals(shared.rankFor(value), service.standing(null, null, id).rank(), id + " at " + value);
                for (ReputationLadder.Rank rank : shared.ranks()) {
                    assertEquals(value >= rank.min() ? 1.0 : 0.0,
                            ReputationFactors.rank(service, FactorContext.builder().param(id + "/" + rank.id())
                                    .build()), id + "/" + rank.id() + " at " + value);
                }
            }
        }
    }

    @Test
    void barsBeyondAndThePageReadAsTheSharedLadderDoes() {
        ReputationLadder shared = ReputationLadder.of(engine.ranks());
        engine.stored.put(HALLOWED, 25_000);
        ReputationChange hallowedUp = service.change(null, null, HALLOWED, 11_000, "t").change();
        assertEquals(ReputationLadder.crossings(25_000, 36_000, shared.top().min(), 5_000),
                hallowedUp.beyondCrossings(), "the Hallowed's Beyond still counts from Exalted's floor");
        assertEquals(shared.progress(36_000, 5_000), hallowedUp.progress(), "and its bar reads as before");
        engine.stored.put(JACK, 1_500);
        ReputationChange jackUp = service.change(null, null, JACK, 100, "t").change();
        assertEquals(shared.progress(1_600, 0), jackUp.progress());

        List<ReputationView.Row> rows = ReputationView.rows(service.met(null, null), service::ladderFor);
        assertEquals(List.of(JACK, HALLOWED), rows.stream().map(ReputationView.Row::id).toList());
        assertEquals(shared.byId("Honored"), rows.get(0).next());
        assertNull(rows.get(1).next());
        assertEquals(Long.valueOf(ReputationLadder.nextRewardAt(36_000, shared.top().min(), 5_000)),
                rows.get(1).nextReward());
    }

    @Test
    void theAuditSaysNothingNewAboutEither() {
        List<ReputationNative.Group> groups = List.of(
                new ReputationNative.Group(JACK, 0, List.of("Hallows_Eve_Old_Jack")),
                new ReputationNative.Group(HALLOWED, 0, List.of()));
        assertEquals(List.of(), ReputationValidator.audit(List.of(jack, hallowed), groups, engine.ranks(),
                group -> true, stat -> true, item -> true));
    }

    @Test
    void theShippedGatesKeepTheirOwnLockLines() throws IOException {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of("hallows_eve_old_jack_favor",
                DerivedFactorAsset.CODEC.decodeAndInheritJsonAsset(
                        RawJsonReader.fromJsonString(fixture("Factors/Hallows_Eve_Old_Jack_Favor.json")), null,
                        new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class,
                                "Hallows_Eve_Old_Jack_Favor", null)))));
        LangCatalog.overrideForTests(Map.of(
                "hallowseve.progression.factor.hallows_eve_old_jack.friendly", "Regular with Old Jack",
                "hallowseve.progression.factor.hallows_eve_old_jack.honored", "Trusted with Old Jack"));
        ReputationFactors.contribute(service);

        assertEquals("hallowseve.progression.factor.hallows_eve_old_jack.friendly",
                FactorNames.name(ReputationFactors.RANK, JACK + "/Friendly").getMessageId(),
                "the pack's own words for its gate still win over the composed line");
        assertEquals("hallowseve.progression.factor.hallows_eve_old_jack.honored",
                FactorNames.name(ReputationFactors.RANK, JACK + "/Honored").getMessageId());
    }
}
