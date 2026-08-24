package com.ziggfreed.common.objectives.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.progress.runtime.ProgressionSystem;

/**
 * The switch registry's mechanics: registration is additive and live, ordering is the order field
 * with the id as the tiebreak, a read that throws paints as UNKNOWN (null) rather than OFF, and a
 * write with no writer or a throwing one refuses without throwing. No display text and no balance
 * value is asserted anywhere - labels are the consumer's, and this registry never reads them.
 */
class SystemSwitchesTest {

    private static final Message LABEL = Message.translation("test.switch.label");

    @BeforeEach
    @AfterEach
    void reset() {
        SystemSwitches.clear();
    }

    private static SystemSwitch sw(String id, int order, BooleanSupplier read,
                                   SystemSwitch.Writer write) {
        return new SystemSwitch(id, ProgressionSystem.QUEST, LABEL, null, read, write, order);
    }

    @Test
    @DisplayName("registration is additive: two owners' switches both list")
    void registrationIsAdditive() {
        SystemSwitch quests = sw("moda:quests", 0, () -> true, on -> { });
        SystemSwitch achievements = sw("modb:achievements", 1, () -> false, null);
        SystemSwitches.register("moda", quests);
        SystemSwitches.register("modb", achievements);
        List<SystemSwitch> all = SystemSwitches.all();
        assertEquals(2, all.size());
        assertSame(quests, all.get(0));
        assertSame(achievements, all.get(1));
    }

    @Test
    @DisplayName("the list is live: a switch registered after the first read appears on the next")
    void listIsLive() {
        SystemSwitches.register("moda", sw("moda:quests", 0, () -> true, null));
        assertEquals(1, SystemSwitches.all().size());
        SystemSwitches.register("modb", sw("modb:achievements", 1, () -> true, null));
        assertEquals(2, SystemSwitches.all().size());
    }

    @Test
    @DisplayName("ordering is the order field ascending, ties broken by id")
    void orderingIsOrderThenId() {
        SystemSwitch last = sw("moda:zz", 5, () -> true, null);
        SystemSwitch tieB = sw("modb:beta", 1, () -> true, null);
        SystemSwitch tieA = sw("modb:alpha", 1, () -> true, null);
        SystemSwitch first = sw("modc:first", 0, () -> true, null);
        SystemSwitches.register("test", last, tieB, tieA, first);
        List<SystemSwitch> all = SystemSwitches.all();
        assertEquals(List.of(first, tieA, tieB, last), all);
    }

    @Test
    @DisplayName("re-registering the same instance is idempotent")
    void reRegisteringSameInstanceIsIdempotent() {
        SystemSwitch quests = sw("moda:quests", 0, () -> true, null);
        SystemSwitches.register("moda", quests);
        SystemSwitches.register("moda", quests);
        assertEquals(1, SystemSwitches.all().size());
        assertSame(quests, SystemSwitches.get("moda:quests"));
    }

    @Test
    @DisplayName("lookup normalizes the id the way the ledger does")
    void lookupNormalizes() {
        SystemSwitch quests = sw("moda:quests", 0, () -> true, null);
        SystemSwitches.register("moda", quests);
        assertSame(quests, SystemSwitches.get("  MODA:Quests  "));
        assertNull(SystemSwitches.get("moda:unknown"));
    }

    @Test
    @DisplayName("a clean read answers the value; a throwing read answers UNKNOWN, never OFF")
    void readGuardedAnswersUnknownOnThrow() {
        SystemSwitch on = sw("moda:on", 0, () -> true, null);
        SystemSwitch off = sw("moda:off", 1, () -> false, null);
        SystemSwitch broken = sw("moda:broken", 2, () -> {
            throw new IllegalStateException("boom");
        }, null);
        SystemSwitches.register("moda", on, off, broken);
        assertEquals(Boolean.TRUE, SystemSwitches.readGuarded(on));
        assertEquals(Boolean.FALSE, SystemSwitches.readGuarded(off));
        assertNull(SystemSwitches.readGuarded(broken));
        // A second read of the broken switch still answers unknown, and still does not throw.
        assertNull(SystemSwitches.readGuarded(broken));
    }

    @Test
    @DisplayName("a write with no writer refuses; a throwing writer refuses; neither throws")
    void writeGuardedRefusesAbsentAndThrowingWriters() {
        SystemSwitch readOnly = sw("moda:readonly", 0, () -> true, null);
        SystemSwitch broken = sw("moda:broken", 1, () -> true, on -> {
            throw new IllegalStateException("boom");
        });
        SystemSwitches.register("moda", readOnly, broken);
        assertTrue(readOnly.readOnly());
        assertFalse(SystemSwitches.writeGuarded(readOnly, true));
        assertFalse(SystemSwitches.writeGuarded(broken, true));
    }

    @Test
    @DisplayName("a working writer is applied and reported applied")
    void writeGuardedAppliesWorkingWriter() {
        AtomicBoolean value = new AtomicBoolean(false);
        SystemSwitch writable = sw("moda:writable", 0, value::get, value::set);
        SystemSwitches.register("moda", writable);
        assertFalse(writable.readOnly());
        assertTrue(SystemSwitches.writeGuarded(writable, true));
        assertEquals(Boolean.TRUE, SystemSwitches.readGuarded(writable));
        assertTrue(SystemSwitches.writeGuarded(writable, false));
        assertEquals(Boolean.FALSE, SystemSwitches.readGuarded(writable));
    }
}
