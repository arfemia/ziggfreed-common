package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A reward kind that pays into the pass it is granted in: it finds the pass's collector on the
 * subject, pays nothing and says so when there is none, never reports a collected reward as handed
 * over, and cannot be deferred. The nested case is the reason the collector rides the subject: a
 * table rolled by a reward is paid through the same subject, so its rewards reach the same collector.
 */
class CollectingRewardKindTest {

    private static final String KIND = "Test_Tally";

    private static final String OWNER = "testmod";

    /** Stands in for whatever a pass settles once it is over. */
    private static final class Tally {

        final List<RewardSpec> collected = new ArrayList<>();
    }

    private RewardKindRegistry kinds;
    private List<String> warnings;
    private List<String> queued;

    @BeforeEach
    void setUp() {
        kinds = new RewardKindRegistry("test");
        kinds.register(KIND, OWNER, CollectingRewardKind.of(KIND, Tally.class,
                (tally, spec) -> tally.collected.add(spec)));
        warnings = new ArrayList<>();
        queued = new ArrayList<>();
    }

    @Nonnull
    private static Subject player() {
        return Subject.of(UUID.randomUUID(), "tester");
    }

    @Nonnull
    private RewardGrants.GrantOutcome grant(@Nonnull List<RewardSpec> rewards, @Nonnull Subject subject) {
        return RewardGrants.grantAll(rewards, subject, "quest:demo", kinds,
                (who, command) -> queued.add(command), warnings::add);
    }

    @Nonnull
    private static LootGrants tallyGrants(@Nonnull String amount) {
        return LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of(KIND, Map.of("Amount", amount))});
    }

    // ==================== paying into the pass ====================

    @Test
    void aGrantCollectsOntoTheCollectorTheSubjectCarries() {
        Tally tally = new Tally();
        RewardSpec spec = RewardSpec.of(KIND, Map.of("Amount", "2"));

        RewardGrants.GrantOutcome outcome = grant(List.of(spec), player().withFacets(tally));

        assertEquals(1, outcome.granted());
        assertEquals(0, outcome.failed());
        assertEquals(1, tally.collected.size(), "the reward reached the pass's collector");
        assertEquals("2", tally.collected.get(0).param("amount"));
        assertTrue(warnings.isEmpty(), () -> "a paid reward warns about nothing: " + warnings);
    }

    @Test
    void aCollectedRewardAddsNothingToTheReceipt() {
        kinds.register("Test_Plain", OWNER, (spec, subject) -> { });
        RewardSpec plain = RewardSpec.of("Test_Plain", Map.of("Amount", "5"));

        RewardGrants.GrantOutcome outcome = grant(
                List.of(RewardSpec.of(KIND, Map.of("Amount", "1")), plain), player().withFacets(new Tally()));

        assertEquals(2, outcome.granted());
        assertEquals(List.of(plain), outcome.receipt(),
                "collected is not handed over, so only the plain reward is on the receipt");
    }

    @Test
    void withNoCollectorTheRewardCountsLostWarnsWithItsKindAndLandsOnTheLedger() {
        RewardGrants.GrantOutcome outcome = grant(List.of(RewardSpec.of(KIND, Map.of("Amount", "1"))), player());

        assertEquals(0, outcome.granted());
        assertEquals(0, outcome.queued());
        assertEquals(1, outcome.failed(), "outside a pass the reward is lost, never pretended paid");
        assertTrue(queued.isEmpty(), "there is no replayable form, so nothing is queued");
        assertTrue(warnings.stream().anyMatch(line -> line.contains("reward lost") && line.contains(KIND)),
                () -> "the loss is warned with the kind's id: " + warnings);

        RegistryLedger.RegistrationInfo info = kinds.info().get(KIND.toLowerCase(Locale.ROOT));
        assertNotNull(info);
        assertEquals(OWNER, info.owner());
        assertEquals(1L, info.failures(), "the failure is counted against the kind's owner");
        assertTrue(info.lastFailure() != null && info.lastFailure().contains("Tally"),
                () -> "the recorded failure names the collector it needed: " + info.lastFailure());
    }

    @Test
    void aGrantWithNoCollectorThrowsNamingTheKindAndTheCollector() {
        RewardHandler handler = kinds.handler(KIND);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> handler.grant(RewardSpec.of(KIND), player()));

        assertTrue(thrown.getMessage().contains(KIND));
        assertTrue(thrown.getMessage().contains("Tally"));
    }

    // ==================== deferred ====================

    @Test
    void aDeferredPayoutDropsItAsAKindThatCannotBeHandedOverLater() {
        Subject claimant = Subject.of(UUID.randomUUID(), "{player}").withFacets(new Tally());

        List<InstanceReward> deferred = DeferredRewards.from(tallyGrants("3"), kinds, claimant,
                "test:table", warnings::add);

        assertEquals(List.of(), deferred, "a pass-scoped reward is never promised for later");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(KIND) && warnings.get(0).contains("cannot be handed over later"),
                () -> "the drop is reported as a no-replay kind: " + warnings);
    }

    // ==================== a nested table ====================

    @Test
    void aNestedTablesRewardReachesTheSameCollectorThroughTheForwardedSubject() {
        // A Lootable reward rolls its table through lootableSinks with the subject it was granted to,
        // which is the pass's subject. Driving that roll here proves the table's own reward reaches
        // the collector the outer pass layered, with no registry copied for the pass.
        Tally tally = new Tally();
        Subject pass = player().withFacets(tally);
        RewardSpec outer = RewardSpec.of(LootRewardKinds.KIND_LOOTABLE, Map.of("Lootable", "demo"));

        LootEngine.Result result = LootEngine.rollAndGrant(
                List.of(Roll.of(null, null, null, null, tallyGrants("3"), null)),
                null, FactorLookup.none(), () -> 0.0,
                LootRewardKinds.lootableSinks(outer, pass, kinds, "reward:demo"));

        assertEquals(1, tally.collected.size(), "the nested table's reward reached the pass's collector");
        assertEquals("3", tally.collected.get(0).param("amount"));
        assertEquals(1, result.getRewardsPaid());
        assertEquals(0, result.getRewardsLost());
        assertTrue(result.getRewardReceipt().isEmpty(), "a collected reward is not reported as handed over");
    }

    @Test
    void theSameNestedTableRolledOutsideAPassCountsItLost() {
        RewardSpec outer = RewardSpec.of(LootRewardKinds.KIND_LOOTABLE, Map.of("Lootable", "demo"));

        LootEngine.Result result = LootEngine.rollAndGrant(
                List.of(Roll.of(null, null, null, null, tallyGrants("3"), null)),
                null, FactorLookup.none(), () -> 0.0,
                LootRewardKinds.lootableSinks(outer, player(), kinds, "reward:demo"));

        assertEquals(0, result.getRewardsPaid());
        assertEquals(1, result.getRewardsLost(), "with no collector on the subject the reward is lost");
        assertEquals(1L, kinds.info().get(KIND.toLowerCase(Locale.ROOT)).failures());
    }

    // ==================== the site marker ====================

    @Test
    void collectorOfAnswersTheCollectorOfACollectingKindOnly() {
        kinds.register("Test_Plain", OWNER, (spec, subject) -> { });

        assertSame(Tally.class, CollectingRewardKind.collectorOf(kinds, KIND));
        assertSame(Tally.class, CollectingRewardKind.collectorOf(kinds, "TEST_TALLY"),
                "a kind id matches without regard to case");
        assertNull(CollectingRewardKind.collectorOf(kinds, "Test_Plain"), "a plain kind collects nothing");
        assertNull(CollectingRewardKind.collectorOf(kinds, "Nobody_Registered_This"));
        assertNull(CollectingRewardKind.collectorOf(null, KIND));
    }

    @Test
    void theSiteWarningNamesTheKindAndItsCollectorAndIsOnlyAWarning() {
        kinds.register("Test_Plain", OWNER, (spec, subject) -> { });

        Finding finding = CollectingRewardKind.siteWarning("quest", kinds, KIND, "Rewards", "my_quest");

        assertNotNull(finding);
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals(CollectingRewardKind.SITE_CODE, finding.code());
        assertEquals("quest", finding.domain());
        assertTrue(finding.message().contains(KIND) && finding.message().contains("Tally"),
                () -> "the finding says which kind and which collector: " + finding.message());
        assertNull(CollectingRewardKind.siteWarning("quest", kinds, "Test_Plain", "Rewards", "my_quest"),
                "a kind that pays anywhere is no site's concern");
    }

    @Test
    void theKindExposesWhatItWasBuiltFor() {
        CollectingRewardKind<Tally> kind = CollectingRewardKind.of(" Test_Other ", Tally.class, (t, s) -> { });

        assertEquals("Test_Other", kind.kindId());
        assertSame(Tally.class, kind.collector());
        assertNull(kind.retryCommand(RewardSpec.of("Test_Other"), player(), "quest:demo"),
                "a pass-scoped reward has no replayable form");
    }

    @Test
    void aKindMissingItsIdOrCollectorIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> CollectingRewardKind.of(" ", Tally.class, (t, s) -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> CollectingRewardKind.<Tally>of(KIND, null, (t, s) -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> CollectingRewardKind.of(KIND, Tally.class, null));
    }
}
