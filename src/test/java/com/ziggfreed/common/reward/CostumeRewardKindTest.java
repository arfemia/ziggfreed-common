package com.ziggfreed.common.reward;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.effect.costume.Costumes;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * A costume as a reward: it registers under the id content writes, it names the costume it puts on and the
 * table it rolls instead, and it goes on from the wearer's world's task queue, so the payout reports it paid
 * before it lands. A costume that cannot go on rolls its fallback for the same player; with none, or one that
 * cannot be rolled, or a wearer gone before the queue runs, it is one warn line, never a throw into the queue
 * and never parked for a later connect; and a costume paid from inside a fallback roll rolls no fallback of
 * its own, so the queue always empties. Dressing a live player is the in-game smoke's; a unit JVM reaches the
 * engine half only as far as "nobody to dress", and a held queue that drains as the world's does stands in
 * for it.
 */
class CostumeRewardKindTest {

    private static final Subject PLAYER = Subject.of(UUID.randomUUID(), "tester");

    private static final RewardSpec GHOST =
            RewardSpec.of(CostumeRewardKind.KIND, "Costume", "Yourpack_Costume_Ghost");

    private static final RewardSpec GHOST_OR_TREAT = RewardSpec.of(CostumeRewardKind.KIND,
            Map.of("Costume", "Yourpack_Costume_Ghost", "FallbackLootable", "Yourpack_Treats"));

    @Test
    void theKindRegistersUnderItsAuthoredNameInAnyCasing() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        CostumeRewardKind.registerInto(kinds);

        assertTrue(kinds.isRegistered("Costume"));
        assertNotNull(kinds.handler("costume"), "a kind id is matched without regard to case");
    }

    @Test
    void aCostumeIsReportedPaidOnceQueuedAndGoesOnOnlyWhenTheQueueRuns() {
        HeldQueue queue = new HeldQueue(true);
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        List<String> logged = new ArrayList<>();
        kinds.register(CostumeRewardKind.KIND, "test", new CostumeRewardKind(queue, kinds, logged::add));

        RewardGrants.GrantOutcome outcome = RewardGrants.grantAll(List.of(GHOST), PLAYER, "dialogue:test",
                kinds, null, logged::add);

        assertEquals(1, outcome.granted(), "the payout reports it paid before it lands");
        assertTrue(outcome.receipt().isEmpty(), "a receipt names only what reached the player, and this has not yet");
        assertEquals(List.of("tester in Yourpack_Costume_Ghost"), queue.asked);
        assertEquals(1, queue.waiting.size(), "nothing is put on during the payout itself");

        queue.run(Costumes.DressOutcome.DRESSED);
        assertTrue(logged.isEmpty(), "a costume that went on needs no log line: " + logged);
    }

    @Test
    void aCostumeThatCannotGoOnRollsItsFallbackForTheSamePlayerInstead() {
        for (Costumes.DressOutcome refusal : refusals()) {
            HeldQueue queue = new HeldQueue(true);
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            List<String> logged = new ArrayList<>();
            List<String> treats = new ArrayList<>();
            List<String> parked = new ArrayList<>();
            kinds.register(CostumeRewardKind.KIND, "test", new CostumeRewardKind(queue, kinds, logged::add));
            kinds.register(LootRewardKinds.KIND_LOOTABLE, "test", (spec, subject) -> treats.add(subject.name()
                    + " rolls " + spec.param("Lootable") + " for " + spec.param(RewardGrants.P_SOURCE)));

            RewardGrants.GrantOutcome outcome = RewardGrants.grantAll(List.of(GHOST_OR_TREAT), PLAYER,
                    "dialogue:test", kinds, (subject, command) -> parked.add(command), logged::add);
            assertTrue(treats.isEmpty(), refusal + ": nothing is decided before the dressing runs");
            queue.run(refusal);

            assertEquals(1, outcome.granted(), refusal.name());
            assertEquals(List.of("tester rolls Yourpack_Treats for dialogue:test"), treats, refusal.name());
            assertTrue(logged.isEmpty(), refusal + " is covered by its treat, so nothing is lost: " + logged);
            assertTrue(parked.isEmpty(), refusal.name());
        }
    }

    @Test
    void aCostumeThatCannotGoOnWithNoFallbackIsOneWarnLineAndNeverParked() {
        for (Costumes.DressOutcome refusal : refusals()) {
            HeldQueue queue = new HeldQueue(true);
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            List<String> logged = new ArrayList<>();
            List<String> parked = new ArrayList<>();
            kinds.register(CostumeRewardKind.KIND, "test", new CostumeRewardKind(queue, kinds, logged::add));

            RewardGrants.GrantOutcome outcome = RewardGrants.grantAll(List.of(GHOST), PLAYER, "dialogue:test",
                    kinds, (subject, command) -> parked.add(command), logged::add);
            assertDoesNotThrow(() -> queue.run(refusal), refusal.name());

            assertEquals(1, outcome.granted(), refusal.name());
            assertEquals(0, outcome.queued(),
                    refusal + ": a costume put on at a later connect would be a trick out of nowhere");
            assertTrue(parked.isEmpty(), refusal.name());
            assertEquals(1, logged.size(), refusal + " is one warn line: " + logged);
            assertTrue(logged.get(0).contains(refusal.name()) && logged.get(0).contains("dialogue:test"),
                    refusal + " is named in the log with what paid it: " + logged);
            assertEquals(1, kinds.info().get("costume").failures(), refusal + " counts against the kind");
        }
    }

    @Test
    void aFallbackThatCannotBeRolledIsOneWarnLineAndNeverAThrow() {
        HeldQueue queue = new HeldQueue(true);
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        List<String> logged = new ArrayList<>();
        kinds.register(CostumeRewardKind.KIND, "test", new CostumeRewardKind(queue, kinds, logged::add));

        RewardGrants.grantAll(List.of(GHOST_OR_TREAT), PLAYER, "dialogue:test", kinds, null, logged::add);
        assertDoesNotThrow(() -> queue.run(Costumes.DressOutcome.WEARING_ANOTHER));
        assertEquals(1, logged.size(), "no Lootable kind to pay the fallback through: " + logged);
        assertTrue(logged.get(0).contains("Lootable") && logged.get(0).contains("WEARING_ANOTHER"), logged.get(0));

        logged.clear();
        kinds.register(LootRewardKinds.KIND_LOOTABLE, "test", (spec, subject) -> {
            throw new IllegalStateException("lootable '" + spec.param("Lootable") + "' has no rolls to grant");
        });
        RewardGrants.grantAll(List.of(GHOST_OR_TREAT), PLAYER, "dialogue:test", kinds, null, logged::add);
        assertDoesNotThrow(() -> queue.run(Costumes.DressOutcome.LOCKED));
        assertEquals(1, logged.size(), "a table nobody answers to: " + logged);
        assertTrue(logged.get(0).contains("Yourpack_Treats") && logged.get(0).contains("LOCKED"), logged.get(0));
    }

    @Test
    void aCostumePaidFromInsideItsFallbackRollsNoFallbackSoTheQueueEmpties() {
        HeldQueue queue = new HeldQueue(true);
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        List<String> logged = new ArrayList<>();
        List<String> treats = new ArrayList<>();
        kinds.register(CostumeRewardKind.KIND, "test", new CostumeRewardKind(queue, kinds, logged::add));
        // A fallback table that pays the costume back, as a table naming itself, or two naming each other, would.
        kinds.register(LootRewardKinds.KIND_LOOTABLE, "test", (spec, subject) -> {
            treats.add(subject.name() + " rolls " + spec.param("Lootable"));
            RewardGrants.grantAll(List.of(GHOST_OR_TREAT), subject, "reward:Yourpack_Treats", kinds, null,
                    logged::add);
        });

        RewardGrants.grantAll(List.of(GHOST_OR_TREAT), PLAYER, "dialogue:test", kinds, null, logged::add);
        queue.run(Costumes.DressOutcome.WEARING_ANOTHER);

        assertEquals(List.of("tester rolls Yourpack_Treats"), treats, "one treat, never a second roll");
        assertEquals(1, logged.size(), "the costume the fallback paid back is refused with one line: " + logged);
        assertTrue(logged.get(0).contains("inside a fallback roll"), logged.get(0));
        assertTrue(queue.waiting.isEmpty(), "the world's queue empties");
    }

    @Test
    void aWearerGoneBeforeTheQueueRunsIsOneWarnLineAndNoTreat() {
        HeldQueue queue = new HeldQueue(true);
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        List<String> logged = new ArrayList<>();
        List<String> treats = new ArrayList<>();
        kinds.register(CostumeRewardKind.KIND, "test", new CostumeRewardKind(queue, kinds, logged::add));
        kinds.register(LootRewardKinds.KIND_LOOTABLE, "test", (spec, subject) -> treats.add(subject.name()));

        RewardGrants.grantAll(List.of(GHOST_OR_TREAT), PLAYER, "dialogue:test", kinds, null, logged::add);
        assertDoesNotThrow(() -> queue.run(null));

        assertTrue(treats.isEmpty(), "a treat needs the same live player, so none is rolled");
        assertEquals(1, logged.size(), "one line: " + logged);
        assertTrue(logged.get(0).contains("Yourpack_Costume_Ghost") && logged.get(0).contains("gone"), logged.get(0));
        assertEquals(1, kinds.info().get("costume").failures(), "counted against the kind");
    }

    @Test
    void theCostumeIsReadInAnyCasingWithEffectAsTheFallbackName() {
        assertEquals("Yourpack_Costume_Ghost",
                CostumeRewardKind.costumeOf(RewardSpec.of("Costume", "costume", " Yourpack_Costume_Ghost ")));
        assertEquals("Yourpack_Costume_Bat",
                CostumeRewardKind.costumeOf(RewardSpec.of("Costume", "Effect", "Yourpack_Costume_Bat")));
        assertEquals("Yourpack_Costume_Ghost", CostumeRewardKind.costumeOf(RewardSpec.of("Costume",
                Map.of("Costume", "Yourpack_Costume_Ghost", "Effect", "Yourpack_Costume_Bat"))),
                "Costume wins when both are written");
        assertEquals("", CostumeRewardKind.costumeOf(RewardSpec.of("Costume")));
    }

    @Test
    void theFallbackTableIsReadInAnyCasingAndTrimmed() {
        assertEquals("Yourpack_Treats", CostumeRewardKind.fallbackOf(GHOST_OR_TREAT));
        assertEquals("Yourpack_Treats",
                CostumeRewardKind.fallbackOf(RewardSpec.of("Costume", "fallbacklootable", " Yourpack_Treats ")));
        assertEquals("", CostumeRewardKind.fallbackOf(GHOST));
    }

    @Test
    void aRewardNamingNoCostumeFailsNamingTheParameter() {
        CostumeRewardKind kind = new CostumeRewardKind((wearer, costume, then) -> {
            throw new AssertionError("nothing should be put on");
        }, new RewardKindRegistry("test"), line -> { });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> kind.grant(RewardSpec.of(CostumeRewardKind.KIND, "Costume", "  "), PLAYER));
        assertTrue(failure.getMessage().contains("'Costume'"), failure.getMessage());
    }

    @Test
    void withNobodyLiveToDressTheRewardIsLostAtOnceAndNeverParked() {
        assertFalse(CostumeRewardKind.queueOnWorld(Subject.of(UUID.randomUUID(), "offline"),
                "Yourpack_Costume_Ghost", outcome -> {
                    throw new AssertionError("nothing is queued for nobody");
                }), "the engine half queues nothing without a live player");

        HeldQueue closed = new HeldQueue(false);
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        List<String> logged = new ArrayList<>();
        List<String> treats = new ArrayList<>();
        List<String> parked = new ArrayList<>();
        kinds.register(CostumeRewardKind.KIND, "test", new CostumeRewardKind(closed, kinds, logged::add));
        kinds.register(LootRewardKinds.KIND_LOOTABLE, "test", (spec, subject) -> treats.add(subject.name()));

        RewardGrants.GrantOutcome outcome = RewardGrants.grantAll(List.of(GHOST_OR_TREAT), PLAYER,
                "dialogue:test", kinds, (subject, command) -> parked.add(command), logged::add);

        assertEquals(1, outcome.failed(), "known at the payout, so counted lost there");
        assertTrue(parked.isEmpty(), "never parked for a later connect");
        assertTrue(treats.isEmpty(), "a treat needs the same live player, so none is rolled");
        assertEquals(1, logged.size(), "one line: " + logged);
        assertTrue(logged.get(0).contains("Yourpack_Costume_Ghost"), logged.get(0));
    }

    // ==================== helpers ====================

    /** Every outcome that leaves the wearer as they were. */
    @Nonnull
    private static List<Costumes.DressOutcome> refusals() {
        return Arrays.stream(Costumes.DressOutcome.values())
                .filter(outcome -> outcome != Costumes.DressOutcome.DRESSED)
                .toList();
    }

    /** The wearer's world task queue, held still: what was asked for, and what waits to run. */
    private static final class HeldQueue implements CostumeRewardKind.Wardrobe {

        private final boolean accepting;
        final List<String> asked = new ArrayList<>();
        final List<Consumer<Costumes.DressOutcome>> waiting = new ArrayList<>();

        /** @param accepting false stands for nobody live to dress, or a world that takes no more tasks */
        HeldQueue(boolean accepting) {
            this.accepting = accepting;
        }

        @Override
        public boolean queue(@Nonnull Subject wearer, @Nonnull String costumeId,
                @Nonnull Consumer<Costumes.DressOutcome> then) {
            asked.add(wearer.name() + " in " + costumeId);
            if (accepting) {
                waiting.add(then);
            }
            return accepting;
        }

        /**
         * The world drains its queue as its own {@code consumeTaskQueue} does, running in the same pass
         * whatever a task queues, and every dressing comes to {@code outcome} (null: the wearer was gone).
         * Ten tasks in one pass is a fallback re-rolling for ever, which would hang the world thread.
         */
        void run(@Nullable Costumes.DressOutcome outcome) {
            for (int ran = 0; !waiting.isEmpty(); ran++) {
                assertTrue(ran < 10, "the queue never empties: a fallback re-rolls for ever");
                waiting.remove(0).accept(outcome);
            }
        }
    }
}
