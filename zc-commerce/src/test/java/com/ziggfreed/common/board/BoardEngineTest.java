package com.ziggfreed.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.CostEngine;
import com.ziggfreed.common.currency.CurrencyCatalog;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.currency.ItemWallet;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.rotation.RotationSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.PeriodMath;

/** A board as a rotating view: the draw, the period lock, the accept site, and the reroll order. */
class BoardEngineTest {

    private static final Subject SUBJECT = Subject.of(UUID.randomUUID(), "Tester");
    private static final long DAY_ONE = 100L * PeriodMath.DAY_MS + 3600_000L;
    private static final long DAY_TWO = DAY_ONE + PeriodMath.DAY_MS;

    /** A bounty is an id plus the one board it appears on here. Everything else belongs to the quest. */
    private record TestBounty(String id, String board, String difficulty) implements BountyRef {

        @Override
        @Nonnull
        public String bountyId() {
            return id;
        }

        @Override
        public boolean isOn(@Nonnull String boardId) {
            return board.equalsIgnoreCase(boardId);
        }

        @Override
        @Nullable
        public String difficultyOn(@Nonnull String boardId) {
            return isOn(boardId) ? difficulty : null;
        }
    }

    /** A board assembled in the test, standing in for whatever the authoring layer produces. */
    private record TestBoard(String id, List<PoolSlot> positions, @Nullable RerollSpec rerollSpec,
            Map<String, GateSpec> acceptGates) implements BoardSpec {

        @Override
        @Nonnull
        public String boardId() {
            return id;
        }

        @Override
        @Nonnull
        public List<PoolSlot> slots() {
            return positions;
        }

        @Override
        @Nullable
        public RerollSpec reroll() {
            return rerollSpec;
        }

        @Override
        @Nonnull
        public Map<String, GateSpec> acceptRequires() {
            return acceptGates;
        }

        @Override
        @Nonnull
        public RotationSpec rotation() {
            return RotationSpec.daily();
        }
    }

    /** The quest lifecycle as a map, so the board's own decisions are driven by numbers. */
    private static final class FakeQuests implements BoardQuests {
        final Map<String, String> acceptedAt = new HashMap<>();
        final Map<String, Long> completedAt = new HashMap<>();
        final List<String> reArmed = new ArrayList<>();
        boolean refuseAccept;

        @Override
        public boolean accept(@Nonnull Subject subject, @Nonnull String bountyId, @Nonnull String boardId) {
            if (refuseAccept) {
                return false;
            }
            acceptedAt.put(bountyId, boardId);
            return true;
        }

        @Override
        public boolean isCarried(@Nonnull Subject subject, @Nonnull String bountyId) {
            return acceptedAt.containsKey(bountyId);
        }

        @Override
        public long lastCompletionMs(@Nonnull Subject subject, @Nonnull String bountyId) {
            return completedAt.getOrDefault(bountyId, 0L);
        }

        @Override
        public void reArm(@Nonnull Subject subject, @Nonnull String bountyId) {
            reArmed.add(bountyId);
            completedAt.remove(bountyId);
        }

        @Override
        @Nullable
        public String acceptedAt(@Nonnull Subject subject, @Nonnull String bountyId) {
            return acceptedAt.get(bountyId);
        }
    }

    private InMemoryCommerceStore store;
    private FakeQuests quests;
    private CurrencyEngine currencies;
    private CostEngine costs;
    private BoardEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryCommerceStore();
        quests = new FakeQuests();
        currencies = CurrencyEngine.builder()
                .catalog(CurrencyCatalog.of(List.of(CurrencyDef.builder("Bounty_Token").build())))
                .items(ItemWallet.NONE)
                .store(store)
                .warn(msg -> { })
                .build();
        costs = CostEngine.builder(currencies).items(ItemWallet.NONE).warn(msg -> { }).build();
        engine = BoardEngine.builder(quests, GateEvaluator.builder().warn(msg -> { }).build())
                .costs(costs)
                .store(store)
                .warn(msg -> { })
                .build();
    }

    private static GateSpec gate(String json) throws IOException {
        return GateSpec.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    private static List<BountyRef> pool() {
        List<BountyRef> pool = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            pool.add(new TestBounty("hunt_easy_" + i, "Daily", "Easy"));
        }
        for (int i = 1; i <= 5; i++) {
            pool.add(new TestBounty("hunt_hard_" + i, "Daily", "Hard"));
        }
        pool.add(new TestBounty("weekly_only", "Weekly", "Hard"));
        return pool;
    }

    private static TestBoard daily() {
        return new TestBoard("Daily", List.of(PoolSlot.tier("Easy", 2), PoolSlot.tier("Hard", 1)),
                null, Map.of());
    }

    // ==================== The rotating view ====================

    @Test
    @DisplayName("only bounties naming the board are members of it")
    void membershipFiltersThePool() {
        List<BountyRef> members = engine.membersOf(daily(), pool());
        assertEquals(10, members.size());
        assertTrue(members.stream().noneMatch(ref -> "weekly_only".equals(ref.bountyId())));
    }

    @Test
    @DisplayName("the active set is the same for every look inside one period, and changes with it")
    void theActiveSetIsStablePerPeriod() {
        TestBoard board = daily();
        List<String> morning = engine.activeSet(board, pool(), DAY_ONE).stream()
                .map(BountyRef::bountyId).toList();
        List<String> evening = engine.activeSet(board, pool(), DAY_ONE + 40_000_000L).stream()
                .map(BountyRef::bountyId).toList();
        List<String> tomorrow = engine.activeSet(board, pool(), DAY_TWO).stream()
                .map(BountyRef::bountyId).toList();

        assertEquals(morning, evening);
        assertNotEquals(morning, tomorrow);
        assertEquals(3, morning.size(), "two easy slots and a hard one");
    }

    @Test
    @DisplayName("each drawn position is filled by a bounty of the grade its slot asked for")
    void slotsDrawTheirOwnGrade() {
        List<BountyRef> shown = engine.activeSet(daily(), pool(), DAY_ONE);
        assertTrue(shown.get(0).bountyId().startsWith("hunt_easy_"));
        assertTrue(shown.get(1).bountyId().startsWith("hunt_easy_"));
        assertTrue(shown.get(2).bountyId().startsWith("hunt_hard_"));
    }

    // ==================== Accept ====================

    @Test
    @DisplayName("accepting threads the BOARD as the site, so the hand-in is bound to it")
    void acceptThreadsTheBoardAsTheSite() {
        TestBoard board = daily();
        BountyRef first = engine.activeSet(board, pool(), DAY_ONE).get(0);

        assertTrue(engine.accept(SUBJECT, board, first, DAY_ONE).ok());
        assertEquals("Daily", quests.acceptedAt(SUBJECT, first.bountyId()));
    }

    @Test
    @DisplayName("a bounty that names no membership on this board cannot be taken off it")
    void aNonMemberCannotBeTaken() {
        BountyRef stranger = new TestBounty("weekly_only", "Weekly", "Hard");
        BoardEngine.BoardCheck check = engine.canAccept(SUBJECT, daily(), stranger, DAY_ONE);
        assertFalse(check.ok());
        assertEquals(BoardEngine.REASON_NOT_ON_BOARD, check.reason());
    }

    @Test
    @DisplayName("a bounty already being carried cannot be taken again")
    void alreadyCarriedRefuses() {
        TestBoard board = daily();
        BountyRef first = engine.activeSet(board, pool(), DAY_ONE).get(0);
        engine.accept(SUBJECT, board, first, DAY_ONE);

        BoardEngine.BoardCheck again = engine.canAccept(SUBJECT, board, first, DAY_ONE);
        assertFalse(again.ok());
        assertEquals(BoardEngine.REASON_ALREADY_CARRIED, again.reason());
    }

    @Test
    @DisplayName("a per-grade accept gate is the shared requirement block, refused in its own words")
    void aPerGradeGateIsTheSharedOne() throws IOException {
        TestBoard gated = new TestBoard("Daily", daily().slots(), null,
                Map.of("Hard", gate("{ \"Permission\": \"board.veteran\" }")));

        BountyRef easy = new TestBounty("hunt_easy_1", "Daily", "Easy");
        BountyRef hard = new TestBounty("hunt_hard_1", "Daily", "Hard");

        assertTrue(engine.canAccept(SUBJECT, gated, easy, DAY_ONE).ok(), "an ungraded gate is open");
        BoardEngine.BoardCheck refusal = engine.canAccept(SUBJECT, gated, hard, DAY_ONE);
        assertFalse(refusal.ok());
        assertEquals(GateEvaluator.REASON_PERMISSION, refusal.reason());
    }

    @Test
    @DisplayName("a grade gate is looked up case-insensitively, the way every other id is")
    void gradeGatesMatchCaseInsensitively() throws IOException {
        TestBoard gated = new TestBoard("Daily", daily().slots(), null,
                Map.of("hard", gate("{ \"Permission\": \"board.veteran\" }")));
        assertNotNull(engine.acceptGateFor(gated, "HARD"));
        assertNull(engine.acceptGateFor(gated, "Easy"));
        assertNull(engine.acceptGateFor(gated, null));
    }

    @Test
    @DisplayName("a quest engine that refuses the accept is reported rather than assumed to have worked")
    void aRefusedAcceptIsReported() {
        quests.refuseAccept = true;
        TestBoard board = daily();
        BountyRef first = engine.activeSet(board, pool(), DAY_ONE).get(0);

        BoardEngine.BoardCheck check = engine.accept(SUBJECT, board, first, DAY_ONE);
        assertFalse(check.ok());
        assertEquals(BoardEngine.REASON_REFUSED, check.reason());
    }

    // ==================== The period lock ====================

    @Test
    @DisplayName("a bounty completed this period is spent until the board turns over")
    void completingLocksForThePeriod() {
        TestBoard board = daily();
        BountyRef first = engine.activeSet(board, pool(), DAY_ONE).get(0);
        quests.completedAt.put(first.bountyId(), DAY_ONE);

        assertTrue(engine.completedThisPeriod(SUBJECT, board, first.bountyId(), DAY_ONE));
        BoardEngine.BoardCheck check = engine.canAccept(SUBJECT, board, first, DAY_ONE);
        assertFalse(check.ok());
        assertEquals(BoardEngine.REASON_SPENT_THIS_PERIOD, check.reason());
    }

    @Test
    @DisplayName("the same completion no longer locks once the board has turned over")
    void thePeriodLockLifts() {
        TestBoard board = daily();
        quests.completedAt.put("hunt_easy_1", DAY_ONE);
        assertFalse(engine.completedThisPeriod(SUBJECT, board, "hunt_easy_1", DAY_TWO));
    }

    @Test
    @DisplayName("a lapsed completion is re-armed when the board is next looked at")
    void lapsedCompletionsAreReArmed() {
        TestBoard board = daily();
        quests.completedAt.put("hunt_easy_1", DAY_ONE);
        quests.completedAt.put("hunt_hard_1", DAY_TWO);

        int reArmed = engine.reArmLapsed(SUBJECT, board, pool(), DAY_TWO);

        assertEquals(1, reArmed);
        assertEquals(List.of("hunt_easy_1"), quests.reArmed);
    }

    @Test
    @DisplayName("a bounty being carried is never re-armed, so nothing in progress is discarded")
    void carriedBountiesAreLeftAlone() {
        TestBoard board = daily();
        quests.completedAt.put("hunt_easy_1", DAY_ONE);
        quests.acceptedAt.put("hunt_easy_1", "Daily");

        assertEquals(0, engine.reArmLapsed(SUBJECT, board, pool(), DAY_TWO));
        assertTrue(quests.reArmed.isEmpty());
    }

    // ==================== Reroll ====================

    @Test
    @DisplayName("a board offering no reroll refuses one rather than giving it away")
    void noRerollSpecRefuses() {
        BoardEngine.RerollResult result = engine.reroll(SUBJECT, daily(), pool(), 0, DAY_ONE);
        assertFalse(result.ok());
        assertEquals(BoardEngine.REASON_NO_REROLL, result.reason());
    }

    @Test
    @DisplayName("a paid reroll swaps the position, charges once, and remembers both ids")
    void aPaidRerollSwapsAndCharges() {
        currencies.credit(SUBJECT, "Bounty_Token", 100);
        TestBoard board = new TestBoard("Daily", daily().slots(),
                RerollSpec.of(Cost.single("Bounty_Token", 25), 3), Map.of());

        String before = engine.activeSetFor(SUBJECT, board, pool(), DAY_ONE).get(0).bountyId();
        BoardEngine.RerollResult result = engine.reroll(SUBJECT, board, pool(), 0, DAY_ONE);

        assertTrue(result.ok());
        assertEquals(before, result.replacedId());
        assertNotEquals(before, result.newId());
        assertEquals(75L, currencies.balance(SUBJECT, "Bounty_Token"));
        assertEquals(result.newId(), engine.activeSetFor(SUBJECT, board, pool(), DAY_ONE).get(0).bountyId());
    }

    @Test
    @DisplayName("a reroll respects the slot's grade, so a position never changes what it is for")
    void aRerollKeepsTheGrade() {
        currencies.credit(SUBJECT, "Bounty_Token", 100);
        TestBoard board = new TestBoard("Daily", daily().slots(),
                RerollSpec.of(Cost.single("Bounty_Token", 25), 3), Map.of());

        BoardEngine.RerollResult result = engine.reroll(SUBJECT, board, pool(), 2, DAY_ONE);
        assertTrue(result.ok());
        assertTrue(result.newId().startsWith("hunt_hard_"));
    }

    @Test
    @DisplayName("the cap is reached and every reroll after it is refused, having charged nothing")
    void theRerollCapHolds() {
        currencies.credit(SUBJECT, "Bounty_Token", 1000);
        TestBoard board = new TestBoard("Daily", List.of(PoolSlot.tier("Easy", 2)),
                RerollSpec.of(Cost.single("Bounty_Token", 25), 1), Map.of());

        assertTrue(engine.reroll(SUBJECT, board, pool(), 0, DAY_ONE).ok());
        long afterFirst = currencies.balance(SUBJECT, "Bounty_Token");

        BoardEngine.RerollResult second = engine.reroll(SUBJECT, board, pool(), 1, DAY_ONE);
        assertFalse(second.ok());
        assertEquals(BoardEngine.REASON_REROLL_CAP, second.reason());
        assertEquals(afterFirst, currencies.balance(SUBJECT, "Bounty_Token"), "nothing was charged");
    }

    @Test
    @DisplayName("nothing is charged when there is nothing different to swap in")
    void noAlternativeChargesNothing() {
        currencies.credit(SUBJECT, "Bounty_Token", 100);
        List<BountyRef> tiny = List.of(new TestBounty("only_one", "Daily", "Easy"));
        TestBoard board = new TestBoard("Daily", List.of(PoolSlot.tier("Easy", 1)),
                RerollSpec.of(Cost.single("Bounty_Token", 25), 3), Map.of());

        BoardEngine.RerollResult result = engine.reroll(SUBJECT, board, tiny, 0, DAY_ONE);
        assertFalse(result.ok());
        assertEquals(BoardEngine.REASON_NO_ALTERNATIVE, result.reason());
        assertEquals(100L, currencies.balance(SUBJECT, "Bounty_Token"));
    }

    @Test
    @DisplayName("a reroll nobody can afford is refused and the position is untouched")
    void anUnaffordableRerollChangesNothing() {
        TestBoard board = new TestBoard("Daily", daily().slots(),
                RerollSpec.of(Cost.single("Bounty_Token", 25), 3), Map.of());

        String before = engine.activeSetFor(SUBJECT, board, pool(), DAY_ONE).get(0).bountyId();
        BoardEngine.RerollResult result = engine.reroll(SUBJECT, board, pool(), 0, DAY_ONE);

        assertFalse(result.ok());
        assertEquals(BoardEngine.REASON_REROLL_CANNOT_PAY, result.reason());
        assertEquals(before, engine.activeSetFor(SUBJECT, board, pool(), DAY_ONE).get(0).bountyId());
    }

    @Test
    @DisplayName("a reroll never hands back something that already sat at that position this period")
    void aRerollNeverCyclesBack() {
        currencies.credit(SUBJECT, "Bounty_Token", 1000);
        TestBoard board = new TestBoard("Daily", List.of(PoolSlot.tier("Easy", 1)),
                RerollSpec.of(Cost.single("Bounty_Token", 10), 0), Map.of());

        List<String> seen = new ArrayList<>();
        seen.add(engine.activeSetFor(SUBJECT, board, pool(), DAY_ONE).get(0).bountyId());
        for (int i = 0; i < 4; i++) {
            BoardEngine.RerollResult result = engine.reroll(SUBJECT, board, pool(), 0, DAY_ONE);
            assertTrue(result.ok(), "five easy bounties, so four rerolls all have somewhere to go");
            assertFalse(seen.contains(result.newId()), "already offered here this period");
            seen.add(result.newId());
        }
        assertEquals(5, seen.size());

        BoardEngine.RerollResult exhausted = engine.reroll(SUBJECT, board, pool(), 0, DAY_ONE);
        assertFalse(exhausted.ok());
        assertEquals(BoardEngine.REASON_NO_ALTERNATIVE, exhausted.reason());
    }

    @Test
    @DisplayName("reroll state dies with the period, so a new board comes with fresh rerolls")
    void rerollStateDiesWithThePeriod() {
        currencies.credit(SUBJECT, "Bounty_Token", 1000);
        TestBoard board = new TestBoard("Daily", List.of(PoolSlot.tier("Easy", 1)),
                RerollSpec.of(Cost.single("Bounty_Token", 10), 1), Map.of());

        assertTrue(engine.reroll(SUBJECT, board, pool(), 0, DAY_ONE).ok());
        assertFalse(engine.reroll(SUBJECT, board, pool(), 0, DAY_ONE).ok());
        assertTrue(engine.reroll(SUBJECT, board, pool(), 0, DAY_TWO).ok());
    }

    @Test
    @DisplayName("the probe answers before any price is drained, so a listing can grey the button out")
    void theProbeIsAvailableWithoutCharging() {
        currencies.credit(SUBJECT, "Bounty_Token", 100);
        TestBoard board = new TestBoard("Daily", daily().slots(),
                RerollSpec.of(Cost.single("Bounty_Token", 25), 3), Map.of());

        assertTrue(engine.canReroll(SUBJECT, board, pool(), 0, DAY_ONE).ok());
        assertEquals(100L, currencies.balance(SUBJECT, "Bounty_Token"));
    }
}
