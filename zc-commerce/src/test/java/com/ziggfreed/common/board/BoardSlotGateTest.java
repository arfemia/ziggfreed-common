package com.ziggfreed.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateRefusal;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.rotation.RotationSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.PeriodMath;

/**
 * A slot's own accept gate: the board is drawn for everyone, so a gated slot's contract is always posted;
 * a player who fails the gate cannot take it, one who passes can, the same contract in an open slot is
 * open, a reroll keeps its position's gate, and a locked row lists the slot's reason after the band's.
 */
class BoardSlotGateTest {

    private static final Subject STRANGER = Subject.of(UUID.randomUUID(), "Stranger");
    private static final Subject FRIEND = Subject.of(UUID.randomUUID(), "Friend");
    private static final long DAY_ONE = 100L * PeriodMath.DAY_MS + 3600_000L;

    /** Two Night positions: the first open, the second gated. Distinct instances, as an authored board folds them. */
    private static final PoolSlot OPEN_NIGHT = PoolSlot.tier("Night", 1);
    private static final PoolSlot FAVOR_NIGHT = PoolSlot.of("Night", null, 1, true);

    private record Contract(String id) implements BountyRef {

        @Override
        @Nonnull
        public String bountyId() {
            return id;
        }

        @Override
        public boolean isOn(@Nonnull String boardId) {
            return "Daily".equalsIgnoreCase(boardId);
        }

        @Override
        @Nullable
        public String difficultyOn(@Nonnull String boardId) {
            return isOn(boardId) ? "Night" : null;
        }
    }

    private record GatedBoard(@Nullable GateSpec favorGate, Map<String, GateSpec> bandGates,
            @Nullable RerollSpec rerollSpec) implements BoardSpec {

        @Override
        @Nonnull
        public String boardId() {
            return "Daily";
        }

        @Override
        @Nonnull
        public List<PoolSlot> slots() {
            return List.of(OPEN_NIGHT, FAVOR_NIGHT);
        }

        @Override
        @Nullable
        public GateSpec slotRequires(int slotIndex) {
            return slotIndex == 1 ? favorGate : null;
        }

        @Override
        @Nonnull
        public Map<String, GateSpec> acceptRequires() {
            return bandGates;
        }

        @Override
        @Nullable
        public RerollSpec reroll() {
            return rerollSpec;
        }

        @Override
        @Nonnull
        public RotationSpec rotation() {
            return RotationSpec.daily();
        }
    }

    private static final class Quests implements BoardQuests {
        final Map<String, String> acceptedAt = new HashMap<>();

        @Override
        public boolean accept(@Nonnull Subject subject, @Nonnull String bountyId, @Nonnull String boardId) {
            acceptedAt.put(subject.name() + "/" + bountyId, boardId);
            return true;
        }

        @Override
        public boolean isCarried(@Nonnull Subject subject, @Nonnull String bountyId) {
            return acceptedAt.containsKey(subject.name() + "/" + bountyId);
        }

        @Override
        public long lastCompletionMs(@Nonnull Subject subject, @Nonnull String bountyId) {
            return 0L;
        }
    }

    private BoardEngine engine;
    private List<BountyRef> pool;

    @BeforeEach
    void setUp() {
        // The gate's one requirement is a finished quest, which only FRIEND has.
        GateEvaluator gates = GateEvaluator.builder()
                .completedQuests((subject, questId) -> subject.equals(FRIEND) && "intro".equals(questId))
                .warn(msg -> { })
                .build();
        engine = BoardEngine.builder(new Quests(), gates).store(new InMemoryCommerceStore()).warn(msg -> { }).build();
        pool = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            pool.add(new Contract("night_" + i));
        }
    }

    private static GateSpec gate(String json) throws IOException {
        return GateSpec.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    private GatedBoard board() throws IOException {
        return new GatedBoard(gate("{ \"Quests\": [ \"intro\" ] }"), Map.of(), null);
    }

    @Test
    void theGatedSlotsContractIsPostedForEveryoneAndTakenOnlyByWhoeverPasses() throws IOException {
        GatedBoard board = board();
        List<BountyRef> shown = engine.activeSet(board, pool, DAY_ONE);
        assertEquals(2, shown.size(), "a gate never changes the draw");
        BountyRef open = shown.get(0);
        BountyRef favor = shown.get(1);

        PoolSlot openSlot = engine.postedSlot(STRANGER, board, pool, open.bountyId(), DAY_ONE);
        PoolSlot favorSlot = engine.postedSlot(STRANGER, board, pool, favor.bountyId(), DAY_ONE);
        assertSame(OPEN_NIGHT, openSlot);
        assertSame(FAVOR_NIGHT, favorSlot);

        assertTrue(engine.canAccept(STRANGER, board, open, openSlot, DAY_ONE).ok(), "the open slot is open");
        BoardEngine.BoardCheck locked = engine.canAccept(STRANGER, board, favor, favorSlot, DAY_ONE);
        assertFalse(locked.ok());
        assertEquals(GateEvaluator.REASON_QUEST + "intro", locked.reason(), "refused in the gate's own words");
        assertTrue(engine.canAccept(FRIEND, board, favor, favorSlot, DAY_ONE).ok());
        assertTrue(engine.accept(FRIEND, board, favor, favorSlot, DAY_ONE).ok());
        assertFalse(engine.accept(STRANGER, board, favor, favorSlot, DAY_ONE).ok());
    }

    @Test
    void theOldOverloadsKnowNoSlotAndAContractNotOnShowHasNone() throws IOException {
        GatedBoard board = board();
        BountyRef favor = engine.activeSet(board, pool, DAY_ONE).get(1);
        assertTrue(engine.canAccept(STRANGER, board, favor, DAY_ONE).ok(),
                "a caller that names no slot is ungated by it, so every existing surface behaves as before");
        assertNull(engine.postedSlot(STRANGER, board, pool, "nobody", DAY_ONE));
        assertNull(engine.postedSlot(STRANGER, board, pool, null, DAY_ONE));
        assertNull(engine.slotGateFor(board, null));
        assertNull(engine.slotGateFor(board, PoolSlot.tier("Night", 1)),
                "a slot is matched by instance, so an equal-looking stranger carries no gate");
    }

    @Test
    void aRerollKeepsItsPositionsGate() throws IOException {
        GatedBoard board = new GatedBoard(gate("{ \"Quests\": [ \"intro\" ] }"), Map.of(), RerollSpec.of(Cost.FREE, 3));
        BoardEngine.RerollResult result = engine.reroll(STRANGER, board, pool, 1, DAY_ONE);
        assertTrue(result.ok());
        BountyRef replacement = new Contract(result.newId());
        PoolSlot slot = engine.postedSlot(STRANGER, board, pool, result.newId(), DAY_ONE);
        assertSame(FAVOR_NIGHT, slot, "the rerolled position is still the favor slot");
        assertFalse(engine.canAccept(STRANGER, board, replacement, slot, DAY_ONE).ok());
    }

    @Test
    void aLockedRowListsTheSlotsReasonAfterTheBands() throws IOException {
        GatedBoard board = new GatedBoard(gate("{ \"Quests\": [ \"intro\" ] }"),
                Map.of("Night", gate("{ \"Permission\": \"board.night\" }")), null);
        BountyRef favor = engine.activeSet(board, pool, DAY_ONE).get(1);
        List<GateRefusal> refusals = engine.acceptGateRefusals(STRANGER, board, favor,
                engine.postedSlot(STRANGER, board, pool, favor.bountyId(), DAY_ONE));
        assertEquals(List.of(GateRefusal.Kind.PERMISSION, GateRefusal.Kind.QUEST),
                refusals.stream().map(GateRefusal::kind).toList());
        assertEquals(1, engine.acceptGateRefusals(STRANGER, board, favor).size(), "the old form lists the band's alone");
    }
}
