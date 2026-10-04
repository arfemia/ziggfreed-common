package com.ziggfreed.common.calendar.spawn;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.calendar.CalendarRuntime;
import com.ziggfreed.common.calendar.asset.CalendarSpawnAsset;
import com.ziggfreed.common.calendar.asset.CalendarSpawnConfig;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.util.SafeLog;

/**
 * Keeps every calendar spawn rule in line with what is running: on each tick it works out what each rule
 * should hold ({@link CalendarSpawnPlan}) and writes only what changed. What was written lives in memory
 * only: the engine's store does not keep a runtime write across a restart, so a restart writes afresh.
 */
public final class CalendarSpawns {

    /** Where a rule goes. A seam so the reconciliation is tested without an engine store. */
    @FunctionalInterface
    public interface RuleSink {

        /** Write {@code json} as the world-spawn rule {@code ruleId}; false when the rule was refused and nothing was sent. */
        boolean write(@Nonnull String ruleId, @Nonnull String json);
    }

    /** What each rule was last written as this session, by lower-cased rule id. */
    private static final Map<String, String> WRITTEN = new ConcurrentHashMap<>();

    /** Shared pairs already reported, so a standing conflict warns once. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    @Nonnull
    private static volatile RuleSink sink = new WorldSpawnRuleSink();

    private CalendarSpawns() {
    }

    /** A tick listener: bring every rule in line with what is running now. */
    public static void onTick(@Nonnull CalendarTick tick) {
        reconcile(tick.live());
    }

    /**
     * Bring every rule in line with {@code liveEventIds}. The spawn files are read under the calendar's lock
     * (the lock of {@link CalendarRuntime#service()}), the one a spawn reload folds under: a reload clears
     * the spawn layer before it fills it again, and a read in between would find every file gone and retire
     * each rule. The rules are written outside that lock. Never call this holding the calendar's lock: a
     * call in progress holds this class's lock while it waits for the calendar's, so each would wait for the
     * other.
     */
    public static synchronized void reconcile(@Nonnull Set<String> liveEventIds) {
        Collection<CalendarSpawnAsset> files;
        synchronized (CalendarRuntime.service().lock()) {
            files = CalendarSpawnConfig.getInstance().all().values();
        }
        Map<String, CalendarSpawnAsset> owners = CalendarSpawnPlan.owners(files, liveEventIds);
        reportSharedPairs(owners);
        Map<String, String> writes = CalendarSpawnPlan.writes(WRITTEN, CalendarSpawnPlan.target(WRITTEN, owners));
        for (Map.Entry<String, String> write : writes.entrySet()) {
            if (sink.write(write.getKey(), write.getValue())) {
                WRITTEN.put(write.getKey(), write.getValue());
            }
        }
    }

    private static void reportSharedPairs(@Nonnull Map<String, CalendarSpawnAsset> owners) {
        CalendarSpawnPlan.sharedPairs(owners).forEach((pair, rules) -> {
            if (REPORTED.add(pair + "@" + rules)) {
                int bar = pair.indexOf('|');
                SafeLog.warn("[calendar] the spawn rules " + rules + " both name the role " + pair.substring(0, bar)
                        + " in the environment " + pair.substring(bar + 1) + "; the engine sets up the first and"
                        + " refuses the other");
            }
        });
    }

    /** What each rule was last written as. */
    @Nonnull
    public static Map<String, String> written() {
        return Map.copyOf(WRITTEN);
    }

    public static void useSinkForTests(@Nullable RuleSink testSink) {
        sink = testSink == null ? new WorldSpawnRuleSink() : testSink;
    }

    public static synchronized void resetForTests() {
        WRITTEN.clear();
        REPORTED.clear();
        sink = new WorldSpawnRuleSink();
    }
}
