package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.subject.Subject;

/**
 * The {@code Moment_Item} kind and its collector: a framework kind registered once, each reward's
 * Count summed onto the pass's collector (a nested table's too), the sum resolved once into separate
 * engine copies of the moment's stack, and every Count it cannot pay failing loudly.
 *
 * <p>Tagged {@code engine-items}: an {@link ItemStack}, even a stub one, only loads under the
 * engine's log manager. The stub overrides {@code cleanCopy} so a copy is told apart from the moment
 * without the item asset store a real copy would ask for.
 */
@Tag("engine-items")
class MomentItemsTest {

    private RewardKindRegistry kinds;
    private List<String> warnings;

    @BeforeEach
    void setUp() {
        // The shared registry is process-wide and another class may have cleared it.
        MomentItems.registerInto(RewardKinds.shared());
        kinds = new RewardKindRegistry("test");
        MomentItems.registerInto(kinds);
        LootRewardKinds.registerInto(kinds);
        warnings = new ArrayList<>();
        MomentItems.resetClampWarningForTesting();
    }

    /** A stub stack whose engine copy is a fresh stub of the same item and quantity, counted. */
    private static final class Moment extends ItemStack {

        final List<ItemStack> copies = new ArrayList<>();

        Moment(String id, int count) {
            this.itemId = id;
            this.quantity = count;
        }

        @Override
        @Nonnull
        public ItemStack cleanCopy() {
            Moment copy = new Moment(getItemId(), getQuantity());
            copies.add(copy);
            return copy;
        }
    }

    @Nonnull
    private static Subject player() {
        return Subject.of(UUID.randomUUID(), "tester");
    }

    @Nonnull
    private static RewardSpec moment(@Nonnull String count) {
        return RewardSpec.of(MomentItems.KIND, Map.of("Count", count));
    }

    @Nonnull
    private RewardGrants.GrantOutcome grant(@Nonnull List<RewardSpec> rewards, @Nonnull Subject subject) {
        return RewardGrants.grantAll(rewards, subject, "test:moment", kinds, (who, command) -> { }, warnings::add);
    }

    /** A sample that answers {@code value} and counts how often it was asked. */
    private static DoubleSupplier counting(double value, AtomicInteger draws) {
        return () -> {
            draws.incrementAndGet();
            return value;
        };
    }

    // ==================== registration ====================

    @Test
    void itIsAFrameworkKindRegisteredUnprefixedAndRegisteringTwiceChangesNothing() {
        RewardKindRegistry shared = RewardKinds.shared();
        RewardHandler first = shared.handler(MomentItems.KIND);

        MomentItems.registerInto(shared);

        assertTrue(first instanceof CollectingRewardKind<?>, "a pass-scoped kind is a collecting kind");
        assertSame(first, shared.handler(MomentItems.KIND), "the one handler instance is kept");
        assertEquals("Moment_Item", MomentItems.KIND);
        RegistryLedger.RegistrationInfo info = shared.info().get(MomentItems.KIND.toLowerCase(Locale.ROOT));
        assertEquals(LootRewardKinds.OWNER, info.owner());
        assertSame(MomentItems.class, CollectingRewardKind.collectorOf(shared, "moment_item"));
    }

    @Test
    void itsParameterKeysListTheCount() {
        assertEquals(Map.of(MomentItems.KIND, List.of("count")), MomentItems.parameterKeys());
    }

    @Test
    void aCollectorRefusesAMissingOrEmptyStack() {
        assertThrows(IllegalArgumentException.class, () -> new MomentItems(null));
        assertThrows(IllegalArgumentException.class, () -> new MomentItems(new Moment(null, 1)));
        assertThrows(IllegalArgumentException.class, () -> new MomentItems(new Moment("Empty", 1)));
    }

    // ==================== collecting ====================

    @Test
    void aRewardCollectsItsCountOntoTheCarriedCollector() {
        MomentItems collector = new MomentItems(new Moment("Plant_Fruit", 1));

        RewardGrants.GrantOutcome outcome = grant(List.of(moment("2")), player().withFacets(collector));

        assertEquals(1, outcome.granted());
        assertEquals(0, outcome.failed());
        assertEquals(2.0, collector.tally());
        assertTrue(outcome.receipt().isEmpty(), "collected is not handed over yet");
    }

    @Test
    void aNestedTablesRewardReachesTheSameCollector() {
        MomentItems collector = new MomentItems(new Moment("Plant_Fruit", 1));
        Subject pass = player().withFacets(collector);
        RewardSpec outer = RewardSpec.of(LootRewardKinds.KIND_LOOTABLE, Map.of("Lootable", "demo"));
        LootGrants grants = LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of(MomentItems.KIND, Map.of("Count", "1.5"))});

        LootEngine.Result result = LootEngine.rollAndGrant(List.of(Roll.of(null, null, null, null, grants, null)),
                null, FactorLookup.none(), () -> 0.0, LootRewardKinds.lootableSinks(outer, pass, kinds, "reward:demo"));

        assertEquals(1, result.getRewardsPaid());
        assertEquals(1.5, collector.tally(), "the nested table's reward collected onto the outer pass");
    }

    @Test
    void anUnwrittenCountIsOneCopy() {
        MomentItems collector = new MomentItems(new Moment("Plant_Fruit", 1));

        grant(List.of(RewardSpec.of(MomentItems.KIND)), player().withFacets(collector));

        assertEquals(1.0, collector.tally());
        assertEquals(1, collector.copies(() -> 0.0).size());
    }

    // ==================== resolving ====================

    @Test
    void twoHalvesInOnePassResolveOnceToOneCopyWithNoDraw() {
        MomentItems collector = new MomentItems(new Moment("Plant_Fruit", 1));
        grant(List.of(moment("0.5"), moment("0.5")), player().withFacets(collector));
        AtomicInteger draws = new AtomicInteger();

        List<ItemStack> copies = collector.copies(counting(0.99, draws));

        assertEquals(1, copies.size(), "summed first, so the halves make one whole copy");
        assertEquals(0, draws.get(), "a whole tally consumes no draw");
    }

    @Test
    void aQuarterPaysOneCopyOnlyWhenTheDrawFallsUnderIt() {
        MomentItems under = new MomentItems(new Moment("Plant_Fruit", 1));
        grant(List.of(moment("0.25")), player().withFacets(under));
        AtomicInteger draws = new AtomicInteger();
        assertEquals(1, under.copies(counting(0.1, draws)).size());
        assertEquals(1, draws.get(), "one draw for the fraction");

        MomentItems over = new MomentItems(new Moment("Plant_Fruit", 1));
        grant(List.of(moment("0.25")), player().withFacets(over));
        assertEquals(0, over.copies(() -> 0.9).size());
    }

    @Test
    void eachCopyIsItsOwnEngineCopyNeverTheMomentStack() {
        Moment stack = new Moment("Plant_Fruit", 3);
        MomentItems collector = new MomentItems(stack);
        grant(List.of(moment("3")), player().withFacets(collector));

        List<ItemStack> copies = collector.copies(() -> 0.0);

        assertEquals(3, copies.size(), "three separate stacks, never one oversized one");
        assertEquals(stack.copies, copies, "every copy came from the engine's cleanCopy");
        for (ItemStack copy : copies) {
            assertNotSame(stack, copy);
            assertEquals("Plant_Fruit", copy.getItemId());
            assertEquals(3, copy.getQuantity(), "a copy is the whole moment stack");
        }
        assertNotSame(copies.get(0), copies.get(1));
    }

    @Test
    void resolvingDrainsTheTally() {
        MomentItems collector = new MomentItems(new Moment("Plant_Fruit", 1));
        grant(List.of(moment("2")), player().withFacets(collector));

        assertEquals(2, collector.copies(() -> 0.0).size());
        assertEquals(0.0, collector.tally());
        assertTrue(collector.copies(() -> 0.0).isEmpty(), "a second ask hands over nothing");
    }

    @Test
    void aRunawayTotalIsClampedToTheCeilingAndWarnedOnce() {
        MomentItems collector = new MomentItems(new Moment("Plant_Fruit", 1));
        grant(List.of(moment(Integer.toString(MomentItems.MAX_COPIES * 10))), player().withFacets(collector));

        List<ItemStack> copies = collector.copies(() -> 0.0, warnings::add);

        assertEquals(MomentItems.MAX_COPIES, copies.size());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(MomentItems.KIND) && warnings.get(0).contains("Plant_Fruit"),
                () -> "the warning names the kind and the item: " + warnings);

        grant(List.of(moment(Integer.toString(MomentItems.MAX_COPIES + 1))), player().withFacets(collector));
        assertEquals(MomentItems.MAX_COPIES, collector.copies(() -> 0.0, warnings::add).size());
        assertEquals(1, warnings.size(), "said once per server run, not once per pass");
    }

    @Test
    void aSumThatOverflowsIsClampedToTheCeilingNeverResolvedToNothing() {
        MomentItems collector = new MomentItems(new Moment("Plant_Fruit", 1));
        grant(List.of(moment("1e308"), moment("1e308")), player().withFacets(collector));
        assertTrue(Double.isInfinite(collector.tally()), "two finite Counts overflow the double sum");

        List<ItemStack> copies = collector.copies(() -> 0.0, warnings::add);

        assertEquals(MomentItems.MAX_COPIES, copies.size(), "an overflowed total is a runaway, not zero");
        assertEquals(1, warnings.size(), () -> "the clamp is warned: " + warnings);
        assertEquals(0.0, collector.tally());
    }

    // ==================== failing loud ====================

    @Test
    void aCountItCannotPayCountsLostOnTheLedger() {
        for (String bad : List.of("0", "-1", "lots", "NaN", "Infinity")) {
            MomentItems collector = new MomentItems(new Moment("Plant_Fruit", 1));
            long before = kinds.info().get("moment_item").failures();

            RewardGrants.GrantOutcome outcome = grant(List.of(moment(bad)), player().withFacets(collector));

            assertEquals(0, outcome.granted(), bad);
            assertEquals(1, outcome.failed(), () -> "a Count of " + bad + " is lost, never paid");
            assertEquals(before + 1, kinds.info().get("moment_item").failures(), bad);
            assertEquals(0.0, collector.tally(), bad);
        }
    }

    @Test
    void withNoCollectorTheRewardCountsLost() {
        RewardGrants.GrantOutcome outcome = grant(List.of(moment("1")), player());

        assertEquals(1, outcome.failed());
        assertEquals(1L, kinds.info().get("moment_item").failures());
        assertTrue(warnings.stream().anyMatch(line -> line.contains(MomentItems.KIND)),
                () -> "the loss is warned with the kind's id: " + warnings);
    }
}
