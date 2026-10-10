package com.ziggfreed.common.playerstats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ZoneRef;
import com.ziggfreed.common.util.SafeLog;

/**
 * A season's page lines, kept in the player stat store beside its stat files: what a consumer that pages its
 * seasons (the library's Almanac) registers through {@link PlayerStats#season}, so its per-season tallies live
 * in the one store rather than a record of its own.
 *
 * <p><b>Why a line is not a stat file.</b> A line's tallies keep the page's own keys: {@code <event>/<line>}
 * for every season ({@link StatKeys#line}) in {@code Tallies}, and {@code <event>@<year>/<line>} for one
 * season-year ({@link StatKeys#season}) in {@code Seasons}, both lower-cased, so a page's saved tallies read
 * on unchanged. A stat id may not carry {@code /}, and a stat counts a moment once where a page adds every
 * line that matches it.
 *
 * <ul>
 *   <li>A matching line while its season is live (on the calendar, {@link Occurrences#liveAllCached}) ADDS the
 *       moment's amount to the every-season tally and to that season-year's.</li>
 *   <li>Between seasons only a line written {@code liveOnly = false} counts, and only toward every season.</li>
 *   <li>A season the calendar does not have switched on counts nothing (off means absent).</li>
 *   <li>An amount of zero or less never counts: a tally never goes down.</li>
 * </ul>
 *
 * <p>Each owner's lines count while its own switch says so, whatever {@code stats.json} says; what they add is
 * handed to the owner's growth sink and ranked on the stat board (when the store is on) like any Sum.
 */
public final class SeasonLines {

    /**
     * One page line.
     *
     * @param eventId  the calendar event the page belongs to, lower-cased
     * @param lineId   the line's name in every player's record, lower-cased
     * @param def      which moments count toward it (the shared objective leaf)
     * @param liveOnly whether it counts only while its season is live
     * @param textKey  the key the line is named by, as authored (resolved through {@code ContentKeys}), or null
     */
    public record Line(@Nonnull String eventId, @Nonnull String lineId, @Nonnull ObjectiveDef def,
                       boolean liveOnly, @Nullable String textKey) {

        public Line {
            eventId = fold(eventId);
            lineId = fold(lineId);
            textKey = textKey == null || textKey.isBlank() ? null : textKey.trim();
        }

        /** The every-season tally's key, {@code <event>/<line>}. */
        @Nonnull
        public String key() {
            return StatKeys.line(eventId, lineId);
        }
    }

    /**
     * One season's page: its lines in the order the page shows them.
     *
     * @param eventId the calendar event, lower-cased
     * @param nameKey the key the page's title is authored under, or null
     * @param order   where the page sits among the owner's others, lowest first
     * @param lines   its lines, in the page's order
     */
    public record Page(@Nonnull String eventId, @Nullable String nameKey, int order, @Nonnull List<Line> lines) {

        public Page {
            eventId = fold(eventId);
            nameKey = nameKey == null || nameKey.isBlank() ? null : nameKey.trim();
            lines = List.copyOf(lines);
        }
    }

    /** Where a season stands: live in the season that opened in {@code year}, or known and between seasons. */
    public record State(boolean live, int year) {

        /** Switched on, not running now. */
        public static final State BETWEEN = new State(false, 0);

        /** Running now, in the season that opened in {@code year}. */
        @Nonnull
        public static State liveIn(int year) {
            return new State(true, year);
        }
    }

    /** The calendar as the lines need it: null for a season it does not have switched on. */
    @FunctionalInterface
    public interface Calendar {
        @Nullable
        State state(@Nonnull String eventId);
    }

    /**
     * One write a moment makes: {@code amount} added under {@code key}, in {@code Seasons} when {@code season},
     * else in {@code Tallies}.
     */
    public record Write(@Nonnull String key, boolean season, long amount) {
    }

    /** Every usable line of a set of pages, filed by the objective kind it counts. */
    public static final class Index {

        /** No pages. */
        public static final Index EMPTY = new Index(List.of(), Map.of(), Map.of(), Map.of());

        private final List<Page> pages;
        private final Map<String, List<Line>> byKind;
        /** Every line's every-season key, lower-cased, to its line. */
        private final Map<String, Line> byKey;
        /** Each event's lines, in the page's order. */
        private final Map<String, List<Line>> byEvent;

        private Index(@Nonnull List<Page> pages, @Nonnull Map<String, List<Line>> byKind,
                @Nonnull Map<String, Line> byKey, @Nonnull Map<String, List<Line>> byEvent) {
            this.pages = pages;
            this.byKind = byKind;
            this.byKey = byKey;
            this.byEvent = byEvent;
        }

        /**
         * The index over {@code pages}: a page or a line whose name the key format cannot hold is skipped with one
         * warning, and a second page of one event keeps the first.
         */
        @Nonnull
        public static Index of(@Nullable Collection<Page> pages) {
            if (pages == null || pages.isEmpty()) {
                return EMPTY;
            }
            List<Page> kept = new ArrayList<>();
            Map<String, List<Line>> byKind = new HashMap<>();
            Map<String, Line> byKey = new HashMap<>();
            Map<String, List<Line>> byEvent = new LinkedHashMap<>();
            Set<String> events = new HashSet<>();
            for (Page page : pages) {
                if (page == null) {
                    continue;
                }
                if (!StatKeys.usableEvent(page.eventId()) || !events.add(page.eventId())) {
                    SafeLog.warn("[stats] the season page '" + page.eventId() + "' is skipped: its name is blank,"
                            + " carries / @ | or :, or names a season already paged");
                    continue;
                }
                List<Line> lines = new ArrayList<>();
                for (Line line : page.lines()) {
                    if (line == null || !line.eventId().equals(page.eventId())) {
                        continue;
                    }
                    if (!StatKeys.usableStatId(line.lineId()) || byKey.containsKey(line.key())) {
                        SafeLog.warn("[stats] the line '" + line.lineId() + "' of season page '" + page.eventId()
                                + "' is skipped: its name is blank, carries / @ | or :, or repeats a line");
                        continue;
                    }
                    lines.add(line);
                    byKey.put(line.key(), line);
                    byKind.computeIfAbsent(line.def().kind().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(line);
                }
                kept.add(new Page(page.eventId(), page.nameKey(), page.order(), lines));
                byEvent.put(page.eventId(), List.copyOf(lines));
            }
            Map<String, List<Line>> frozen = new HashMap<>();
            for (Map.Entry<String, List<Line>> entry : byKind.entrySet()) {
                frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new Index(List.copyOf(kept), Map.copyOf(frozen), Map.copyOf(byKey), Map.copyOf(byEvent));
        }

        /** The pages kept, in the order given. */
        @Nonnull
        public List<Page> pages() {
            return pages;
        }

        /** The lines counting {@code kindId}, matched without regard to case; empty for none. */
        @Nonnull
        public List<Line> forKind(@Nullable String kindId) {
            if (kindId == null) {
                return List.of();
            }
            List<Line> lines = byKind.get(kindId.toLowerCase(Locale.ROOT));
            return lines == null ? List.of() : lines;
        }

        /** The line whose every-season key is {@code key}, matched without regard to case, or null. */
        @Nullable
        public Line line(@Nullable String key) {
            return key == null ? null : byKey.get(fold(key));
        }

        /** {@code eventId}'s lines in the page's order; empty when no page names it. */
        @Nonnull
        public List<Line> linesOf(@Nullable String eventId) {
            List<Line> lines = eventId == null ? null : byEvent.get(fold(eventId));
            return lines == null ? List.of() : lines;
        }

        /**
         * Does a line of {@code eventId}'s page own the season-year copy a counted stat's {@code key} would take
         * ({@code <event>@<year>/<key>})? Then that copy is the line's, never the stat's.
         */
        public boolean owns(@Nonnull String eventId, @Nonnull String key) {
            return byKey.containsKey(fold(StatKeys.line(eventId, key)));
        }
    }

    /** One owner's registration. */
    private record Owner(@Nonnull Index index, @Nonnull BooleanSupplier enabled,
                         @Nonnull Consumer<Map<String, Long>> grew) {

        /** Is the owner switched on? A switch that throws reads as off. */
        boolean on() {
            try {
                return enabled.getAsBoolean();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static final Map<String, Owner> OWNERS = new ConcurrentHashMap<>();

    /** Whether a failed read of the live seasons has been said, so the log says it once. */
    private static final AtomicBoolean WARNED_SEASONS = new AtomicBoolean();

    private SeasonLines() {
    }

    // ==================== The counting rule (pure) ====================

    /**
     * What one moment writes for {@code index}'s lines: every line of its kind that matches its target,
     * qualifier and zone, each counted the way its season stands on {@code calendar}. Pure.
     */
    @Nonnull
    public static List<Write> writes(@Nonnull Index index, @Nonnull Calendar calendar, @Nonnull String kindId,
            @Nonnull String target, @Nullable String qualifier, long amount, @Nullable ZoneRef zone) {
        if (amount <= 0L) {
            return List.of();
        }
        List<Write> out = new ArrayList<>();
        for (Line line : index.forKind(kindId)) {
            if (!line.def().matches(target, qualifier) || !line.def().matchesZone(zone)) {
                continue;
            }
            State state = calendar.state(line.eventId());
            if (state == null) {
                continue;
            }
            if (state.live()) {
                out.add(new Write(line.key(), false, amount));
                out.add(new Write(StatKeys.season(line.eventId(), state.year(), line.lineId()), true, amount));
            } else if (!line.liveOnly()) {
                out.add(new Write(line.key(), false, amount));
            }
        }
        return out;
    }

    /** Apply {@code writes} to {@code record}: each adds to its bag. */
    static void apply(@Nonnull PlayerStatsComponent record, @Nonnull List<Write> writes) {
        for (Write write : writes) {
            if (write.season()) {
                record.moveSeason(StatKind.SUM, write.key(), write.amount());
            } else {
                record.add(write.key(), write.amount());
            }
        }
    }

    // ==================== The registry ====================

    /** Put {@code owner}'s pages in place of what it registered before; none takes it out. */
    static void register(@Nonnull String owner, @Nonnull Index index, @Nonnull BooleanSupplier enabled,
            @Nonnull Consumer<Map<String, Long>> grew) {
        if (index.pages().isEmpty()) {
            OWNERS.remove(owner);
        } else {
            OWNERS.put(owner, new Owner(index, enabled, grew));
        }
    }

    /**
     * Does a line some owner registered own the season-year copy of {@code key} for {@code eventId}? Asked
     * whatever the owner's switch says, so a switched-off page never finds a stat's copy under its key.
     */
    static boolean owns(@Nonnull String eventId, @Nonnull String key) {
        if (OWNERS.isEmpty()) {
            return false;
        }
        for (Owner owner : OWNERS.values()) {
            if (owner.index().owns(eventId, key)) {
                return true;
            }
        }
        return false;
    }

    /** {@code eventId}'s lines, in the page's order, while their owner is switched on; empty otherwise. */
    @Nonnull
    public static List<Line> shownLinesOf(@Nullable String eventId) {
        for (Owner owner : OWNERS.values()) {
            List<Line> lines = owner.index().linesOf(eventId);
            if (!lines.isEmpty()) {
                return owner.on() ? lines : List.of();
            }
        }
        return List.of();
    }

    /** The line whose every-season key is {@code key}, while its owner is switched on; null otherwise. */
    @Nullable
    public static Line shownLine(@Nullable String key) {
        for (Owner owner : OWNERS.values()) {
            Line line = owner.index().line(key);
            if (line != null) {
                return owner.on() ? line : null;
            }
        }
        return null;
    }

    /**
     * Count one moment into the player's record for every owner switched on whose lines name its kind, then
     * hand each owner what its lines added and rank it ({@link PlayerStats}). World thread, inside the
     * producing dispatch.
     */
    static void count(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String kindId,
            @Nonnull String target, @Nullable String qualifier, long amount, @Nullable ZoneRef zone) {
        if (amount <= 0L || OWNERS.isEmpty()) {
            return;
        }
        Calendar calendar = null;
        for (Owner owner : OWNERS.values()) {
            if (owner.index().forKind(kindId).isEmpty() || !owner.on()) {
                continue;
            }
            if (calendar == null) {
                calendar = liveCalendar(System.currentTimeMillis());
            }
            List<Write> writes = writes(owner.index(), calendar, kindId, target, qualifier, amount, zone);
            if (!writes.isEmpty() && !PlayerStats.countSeasonLines(store, ref, writes, owner.grew())) {
                return;
            }
        }
    }

    /**
     * The calendar on the server's own clock: a season in {@link Occurrences#liveAllCached} (walked at most once
     * a second) is live in its run's year, one switched on but not running is between seasons, and any other is
     * absent. A calendar that throws reads every season as absent, and says so once.
     */
    @Nonnull
    private static Calendar liveCalendar(long nowMs) {
        Map<String, Occurrence> live;
        try {
            live = Occurrences.liveAllCached(nowMs);
        } catch (Throwable t) {
            if (WARNED_SEASONS.compareAndSet(false, true)) {
                SafeLog.warn("[stats] the calendar could not say which seasons are live, so no season's page line"
                        + " counts this moment: " + t.getMessage());
            }
            return eventId -> null;
        }
        return eventId -> {
            for (Occurrence run : live.values()) {
                if (run.eventId().equalsIgnoreCase(eventId)) {
                    return State.liveIn(run.year());
                }
            }
            try {
                return Occurrences.source().isEnabled(eventId) ? State.BETWEEN : null;
            } catch (Throwable t) {
                return null;
            }
        };
    }

    /** Every owner's registration gone, for a test starting from nothing. */
    static void resetForTests() {
        OWNERS.clear();
    }

    @Nonnull
    private static String fold(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
