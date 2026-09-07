package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;

/**
 * Per-reward isolation: one bad reward must never cost the player the others, and a failure that can
 * be replayed must become a queued retry rather than a loss.
 */
class RewardGrantsTest {

    private Subject player;
    private RewardKindRegistry kinds;
    private List<String> granted;
    private List<String> queued;
    private List<String> warnings;

    @BeforeEach
    void setUp() {
        player = Subject.of(UUID.randomUUID(), "tester");
        kinds = new RewardKindRegistry();
        granted = new ArrayList<>();
        queued = new ArrayList<>();
        warnings = new ArrayList<>();
        kinds.register("GOOD", (spec, subject) -> granted.add(spec.paramOr("id", "?")));
    }

    private RewardGrants.GrantOutcome grant(@Nonnull List<RewardSpec> rewards) {
        return RewardGrants.grantAll(rewards, player, "quest:demo", kinds,
                (subject, command) -> queued.add(command), warnings::add);
    }

    @Test
    void everyRewardIsAttemptedEvenAfterOneThrows() {
        kinds.register("BROKEN", (spec, subject) -> {
            throw new IllegalStateException("no");
        });

        RewardGrants.GrantOutcome outcome = grant(List.of(
                RewardSpec.of("GOOD", "id", "first"),
                RewardSpec.of("BROKEN"),
                RewardSpec.of("GOOD", "id", "second")));

        assertEquals(List.of("first", "second"), granted,
                "a failure in the middle must not abort the rest of the payout");
        assertEquals(2, outcome.granted());
        assertEquals(1, outcome.failed());
        assertFalse(outcome.complete());
        assertTrue(outcome.anyDelivered());
    }

    @Test
    void aReplayableFailureIsQueuedRatherThanLost() {
        kinds.register("REPLAYABLE", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
                throw new IllegalStateException("player went offline");
            }

            @Override
            @Nullable
            public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                                       @Nonnull String sourceId) {
                return "deliver " + subject.name() + " " + sourceId;
            }
        });

        RewardGrants.GrantOutcome outcome = grant(List.of(RewardSpec.of("REPLAYABLE")));

        assertEquals(List.of("deliver tester quest:demo"), queued);
        assertEquals(1, outcome.queued());
        assertEquals(0, outcome.failed());
        assertTrue(outcome.complete());
        assertTrue(outcome.anyDelivered());
    }

    @Test
    void aFailureWithNowhereToQueueItIsReportedAsLost() {
        kinds.register("BROKEN", (spec, subject) -> {
            throw new IllegalStateException("no");
        });

        RewardGrants.GrantOutcome outcome = RewardGrants.grantAll(
                List.of(RewardSpec.of("BROKEN")), player, "quest:demo", kinds, null, warnings::add);

        assertEquals(1, outcome.failed());
        assertTrue(warnings.stream().anyMatch(line -> line.contains("reward lost")));
    }

    @Test
    void aRetryQueueThatItselfFailsLeavesTheRewardLostNotSilentlyDropped() {
        kinds.register("REPLAYABLE", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
                throw new IllegalStateException("no");
            }

            @Override
            public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                                       @Nonnull String sourceId) {
                return "deliver";
            }
        });

        RewardGrants.GrantOutcome outcome = RewardGrants.grantAll(
                List.of(RewardSpec.of("REPLAYABLE")), player, "quest:demo", kinds,
                (subject, command) -> {
                    throw new IllegalStateException("queue is full");
                },
                warnings::add);

        assertEquals(1, outcome.failed());
        assertTrue(warnings.stream().anyMatch(line -> line.contains("retry queue also failed")));
    }

    @Test
    void aHandlerWhoseRetryFactoryThrowsIsStillReportedRatherThanEscaping() {
        kinds.register("REPLAYABLE", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
                throw new IllegalStateException("no");
            }

            @Override
            public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                                       @Nonnull String sourceId) {
                throw new IllegalStateException("cannot build one");
            }
        });

        RewardGrants.GrantOutcome outcome = grant(List.of(RewardSpec.of("REPLAYABLE")));

        assertEquals(1, outcome.failed());
        assertTrue(queued.isEmpty());
    }

    @Test
    void anUnhandledKindIsReportedAndCountsAsFailedWithoutStoppingTheRest() {
        RewardGrants.GrantOutcome outcome = grant(List.of(
                RewardSpec.of("NOBODY_REGISTERED_THIS"),
                RewardSpec.of("GOOD", "id", "first")));

        assertEquals(List.of("first"), granted);
        assertEquals(1, outcome.failed());
        assertTrue(warnings.stream().anyMatch(line -> line.contains("no handler registered")));
    }

    @Test
    void aFailingHandlerIsCountedAgainstItsOwnerInTheLedger() {
        kinds.register("BROKEN", "somebody", (spec, subject) -> {
            throw new IllegalStateException("no");
        });

        grant(List.of(RewardSpec.of("BROKEN")));

        var info = kinds.info().get("broken");
        assertEquals("somebody", info.owner());
        assertEquals(1L, info.failures());
    }

    @Test
    void anEmptyPayoutIsANoOp() {
        RewardGrants.GrantOutcome outcome = grant(List.of());
        assertEquals(RewardGrants.GrantOutcome.EMPTY, outcome);
        assertFalse(outcome.anyDelivered());
        assertTrue(outcome.complete());
    }

    @Test
    void specParametersAreCaseBlindAndFallBackRatherThanThrowing() {
        RewardSpec spec = RewardSpec.of("GOOD", Map.of("Amount", "12", "Ratio", "0.5", "Loud", "true"));

        assertEquals("12", spec.param("amount"));
        assertEquals("12", spec.param("AMOUNT"));
        assertEquals(12L, spec.longParam("Amount", 0L));
        assertEquals(0.5d, spec.doubleParam("ratio", 0d));
        assertTrue(spec.flagParam("loud", false));

        assertEquals(7L, spec.longParam("missing", 7L));
        assertEquals(7L, spec.longParam("loud", 7L), "a value that is not a number at all falls back");
        assertEquals("fallback", spec.paramOr("missing", "fallback"));
        assertFalse(spec.flagParam("missing", false));
    }

    /**
     * Hand-written JSON writes {@code 5.0} and means five, so a whole-number decimal reads as that
     * number rather than falling back. It has to: a preview that reads the same authored field
     * loosely and a payout that reads it strictly is how a player gets shown five of something and
     * handed one. A genuinely fractional value truncates, which leaves a count of zero - and a
     * payout of zero is refused loudly rather than passed off as delivered.
     */
    @Test
    void aWholeNumberWrittenAsADecimalReadsAsThatNumber() {
        RewardSpec spec = RewardSpec.of("GOOD", Map.of("Count", "5.0", "Ratio", "0.5"));

        assertEquals(5L, spec.longParam("count", 1L));
        assertEquals(0L, spec.longParam("ratio", 1L), "a fraction truncates rather than inventing a count");
    }

    // ==================== the two questions asked in front of a pass ====================

    /**
     * A reward that says it is only worth anything in the moment is DROPPED when nobody is there to
     * receive it, and dropping it is not losing it: nothing is owed to anybody afterwards.
     */
    @Test
    void aRewardNotWorthWaitingForIsDroppedRatherThanQueued() {
        kinds.register("BROKEN", (spec, subject) -> {
            throw new IllegalStateException("no");
        });

        RewardGrants.GrantOutcome outcome = RewardGrants.grantAll(
                List.of(RewardSpec.of("BROKEN", RewardGrants.P_QUEUE_IF_OFFLINE, "false")),
                player, "quest:demo", kinds, false,
                (subject, command) -> queued.add(command), warnings::add);

        assertTrue(queued.isEmpty(), "nothing waits for a player it was never worth waiting for");
        assertEquals(0, outcome.granted());
        assertEquals(0, outcome.queued());
        assertEquals(0, outcome.failed(), "dropped because its author said so is not lost");
        assertFalse(outcome.anyDelivered());
    }

    /** The same reward with the player standing there is attempted like any other. */
    @Test
    void thatSameRewardIsAttemptedWhenThePlayerIsHere() {
        RewardGrants.GrantOutcome outcome = RewardGrants.grantAll(
                List.of(RewardSpec.of("GOOD", Map.of("id", "now",
                        RewardGrants.P_QUEUE_IF_OFFLINE, "false"))),
                player, "quest:demo", kinds, true,
                (subject, command) -> queued.add(command), warnings::add);

        assertEquals(List.of("now"), granted);
        assertEquals(1, outcome.granted());
    }

    /**
     * A reward with nobody there and no opinion about waiting is dropped too: the flag is absent, so
     * it reads false, and a payout nobody asked to be parked is not parked.
     */
    @Test
    void aRewardSayingNothingAboutWaitingIsDroppedWhenNobodyIsThere() {
        RewardGrants.GrantOutcome outcome = RewardGrants.grantAll(
                List.of(RewardSpec.of("GOOD", "id", "silent")), player, "quest:demo", kinds, false,
                (subject, command) -> queued.add(command), warnings::add);

        assertTrue(granted.isEmpty());
        assertEquals(0, outcome.granted());
        assertEquals(0, outcome.failed());
    }

    /** What paid out is written onto every reward that does not already name its own source. */
    @Test
    void thePayoutSourceIsStampedOnEveryRewardThatDoesNotNameOne() {
        List<RewardSpec> stamped = RewardGrants.stamped(List.of(
                RewardSpec.of("GOOD", "id", "a"),
                RewardSpec.of("GOOD", Map.of("id", "b", RewardGrants.P_SOURCE, "mine"))),
                "quest:demo");

        assertEquals("quest:demo", stamped.get(0).param(RewardGrants.P_SOURCE),
                "a reward that names no source is labelled with what actually paid it out");
        assertEquals("mine", stamped.get(1).param(RewardGrants.P_SOURCE),
                "a reward that already names one keeps it");
    }

    @Test
    void withReturnsACopyAndLeavesTheOriginalAlone() {
        RewardSpec original = RewardSpec.of("GOOD", "id", "first");
        RewardSpec copy = original.with("id", "second").with("extra", "x");

        assertEquals("first", original.param("id"));
        assertEquals("second", copy.param("id"));
        assertEquals("x", copy.param("extra"));
        assertEquals("GOOD", copy.kind());
    }

    // ==================== the receipt ====================

    /**
     * A handler that implements only the two-argument grant hands over exactly what the spec said,
     * so the default receipt is the spec itself, in grant order.
     */
    @Test
    void theReceiptIsTheSpecItselfForAHandlerThatPaysWhatItSaid() {
        RewardSpec first = RewardSpec.of("GOOD", "id", "first");
        RewardSpec second = RewardSpec.of("GOOD", "id", "second");

        RewardGrants.GrantOutcome outcome = grant(List.of(first, second));

        assertEquals(List.of(first, second), outcome.receipt(),
                "an ordinary handler's receipt is the spec it was handed, in order");
    }

    /** A handler that decides at grant time reports what landed, and the spec is not in the receipt. */
    @Test
    void aRollingHandlerReportsWhatItHandedOverInsteadOfItsSpec() {
        RewardSpec rolledA = RewardSpec.of("Item", Map.of("Item", "Coin_Gold", "Count", "3"));
        RewardSpec rolledB = RewardSpec.of("Item", Map.of("Item", "Gem_Ruby", "Count", "1"));
        kinds.register("ROLL", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
                granted.add("rolled");
            }

            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                    @Nonnull String sourceId, @Nonnull Consumer<RewardSpec> receipt) {
                grant(spec, subject);
                receipt.accept(rolledA);
                receipt.accept(rolledB);
            }
        });
        RewardSpec table = RewardSpec.of("ROLL", "table", "demo");

        RewardGrants.GrantOutcome outcome = grant(List.of(table));

        assertEquals(1, outcome.granted());
        assertEquals(List.of(rolledA, rolledB), outcome.receipt(),
                "the receipt is what the roll produced, never the table's own spec");
        assertFalse(outcome.receipt().contains(table));
    }

    /** A rolling handler whose roll produced nothing contributes no row at all. */
    @Test
    void anEmptyRollContributesNothingToTheReceipt() {
        kinds.register("EMPTY_ROLL", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
            }

            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                    @Nonnull String sourceId, @Nonnull Consumer<RewardSpec> receipt) {
                // Rolled, landed nothing, reported nothing.
            }
        });

        RewardGrants.GrantOutcome outcome = grant(List.of(
                RewardSpec.of("EMPTY_ROLL"), RewardSpec.of("GOOD", "id", "after")));

        assertEquals(2, outcome.granted(), "an empty roll still counts as a grant that ran");
        assertEquals(1, outcome.receipt().size(), "and it adds no row; the reward after it still does");
        assertEquals("after", outcome.receipt().get(0).param("id"));
    }

    /**
     * Only a reward that was handed over NOW is in the receipt: a failed one is absent, a queued one
     * is absent, and whatever a handler wrote onto the receipt before it threw is discarded with it.
     */
    @Test
    void aFailedOrQueuedRewardReportsNothingEvenIfItWroteToTheReceiptFirst() {
        kinds.register("BROKEN", (spec, subject) -> {
            throw new IllegalStateException("no");
        });
        kinds.register("HALF", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
            }

            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                    @Nonnull String sourceId, @Nonnull Consumer<RewardSpec> receipt) {
                receipt.accept(RewardSpec.of("Item", Map.of("Item", "Half_Landed", "Count", "1")));
                throw new IllegalStateException("fell over after reporting");
            }

            @Override
            @Nullable
            public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                    @Nonnull String sourceId) {
                return "give tester Half_Landed";
            }
        });
        RewardSpec good = RewardSpec.of("GOOD", "id", "only");

        RewardGrants.GrantOutcome outcome = grant(List.of(
                RewardSpec.of("BROKEN"), RewardSpec.of("HALF"), good));

        assertEquals(1, outcome.granted());
        assertEquals(1, outcome.queued(), "the half-reported reward was queued for a retry");
        assertEquals(1, outcome.failed());
        assertEquals(List.of(good), outcome.receipt(),
                "neither the lost reward nor the queued one is listed as handed over");
    }

    /** The three-count form still builds, and carries no receipt. */
    @Test
    void theThreeCountConstructorCarriesAnEmptyReceipt() {
        RewardGrants.GrantOutcome outcome = new RewardGrants.GrantOutcome(2, 1, 0);
        assertTrue(outcome.receipt().isEmpty());
        assertEquals(2, outcome.granted());
        assertTrue(RewardGrants.GrantOutcome.EMPTY.receipt().isEmpty());
    }

    /**
     * The rule every completion toast reads its rows through: a payout lists its receipt, and no
     * payout lists NOTHING - never the authored list, which would show a player things they were
     * not given under a headline saying they were.
     */
    @Test
    void aToastAfterNoPayoutListsNothingRatherThanThePromise() {
        RewardSpec rolled = RewardSpec.of("Item", Map.of("Item", "Coin_Gold", "Count", "3"));
        RewardGrants.GrantOutcome paid = new RewardGrants.GrantOutcome(1, 0, 0, List.of(rolled));

        assertEquals(List.of(rolled), RewardGrants.GrantOutcome.receiptOf(paid),
                "a payout lists exactly what it handed over");
        assertTrue(RewardGrants.GrantOutcome.receiptOf(null).isEmpty(),
                "no payout (a parked quest, a refused claim) lists no row at all");
    }
}
