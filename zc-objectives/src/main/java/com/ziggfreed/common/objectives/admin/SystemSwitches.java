package com.ziggfreed.common.objectives.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;

/**
 * The open, additive registry of {@link SystemSwitch}es the progression admin page lists: a
 * consumer registers its switches once at setup, and the page reads them all back, live, on every
 * open. Built over {@link RegistryLedger}, so ids are normalized, every registration is attributed
 * to its owner, a genuine overwrite warns once, and a failing read or write is counted against the
 * switch that failed.
 *
 * <p>The guarded accessors mirror the system-gate posture, inverted for each side's own failure
 * cost: a gate that throws reads OPEN because a broken switch must not shut a system down, and here
 * a READ that throws paints as UNKNOWN rather than OFF (the page must not claim a state nobody
 * could read), while a WRITE that throws (or does not exist) simply refuses - the caller reports
 * the refusal and nothing escapes into the page.
 *
 * <p>A live server registers once and never unregisters; {@link #clear()} is for tests.
 */
public final class SystemSwitches {

    private static final RegistryLedger<SystemSwitch> LEDGER =
            new RegistryLedger<>("progression-admin");

    /** Ids whose read has already been warned about, so a broken supplier logs once, not per paint. */
    private static final Set<String> READ_WARNED = ConcurrentHashMap.newKeySet();

    private SystemSwitches() {
    }

    /**
     * Register {@code switches}, attributed to {@code owner}. Additive: nothing already registered
     * is displaced unless a switch reuses an id, which is the ledger's ordinary warn-once
     * overwrite. A null entry is skipped.
     */
    public static void register(@Nullable String owner, @Nonnull SystemSwitch... switches) {
        for (SystemSwitch sw : switches) {
            if (sw != null) {
                LEDGER.put(sw.id(), owner, sw);
            }
        }
    }

    /** Every registered switch, ordered by {@link SystemSwitch#order()} then id. Live: a fresh list per call. */
    @Nonnull
    public static List<SystemSwitch> all() {
        List<SystemSwitch> out = new ArrayList<>();
        for (String id : LEDGER.ids()) {
            SystemSwitch sw = LEDGER.get(id);
            if (sw != null) {
                out.add(sw);
            }
        }
        out.sort(Comparator.comparingInt(SystemSwitch::order).thenComparing(SystemSwitch::id));
        return out;
    }

    /** The switch registered under {@code id}, or null when nothing is. */
    @Nullable
    public static SystemSwitch get(@Nullable String id) {
        return LEDGER.get(id);
    }

    /**
     * The switch's current value, guarded: {@code null} means UNKNOWN - the read threw, which must
     * never paint as OFF. The failure is counted against the switch and warned about once per id.
     */
    @Nullable
    public static Boolean readGuarded(@Nonnull SystemSwitch sw) {
        try {
            return sw.read().getAsBoolean();
        } catch (Throwable t) {
            LEDGER.recordFailure(sw.id(), "read failed: " + t.getMessage());
            if (READ_WARNED.add(RegistryLedger.normalize(sw.id()))) {
                SafeLog.warn("[progression-admin] the switch '" + sw.id()
                        + "' could not be read and shows as unknown: " + t.getMessage());
            }
            return null;
        }
    }

    /**
     * Set the switch, guarded: true only when the write was actually applied. A read-only switch
     * (no writer) and a throwing writer both refuse with {@code false} - the caller says so to the
     * admin; nothing throws out of here.
     */
    public static boolean writeGuarded(@Nonnull SystemSwitch sw, boolean enabled) {
        SystemSwitch.Writer writer = sw.write();
        if (writer == null) {
            return false;
        }
        try {
            writer.set(enabled);
            return true;
        } catch (Throwable t) {
            LEDGER.recordFailure(sw.id(), "write failed: " + t.getMessage());
            SafeLog.warn("[progression-admin] the switch '" + sw.id()
                    + "' refused the write and keeps its state", t);
            return false;
        }
    }

    /** Drop every registration and the warn-once memory. A live server never calls this; tests reset. */
    public static void clear() {
        LEDGER.clear();
        READ_WARNED.clear();
    }
}
