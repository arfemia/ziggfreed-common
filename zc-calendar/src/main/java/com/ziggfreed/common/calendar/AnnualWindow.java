package com.ziggfreed.common.calendar;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;

/**
 * The runs of an event, year by year: an every-year {@link YearRule} (fixed month-days, several spans of them,
 * a span around Easter Sunday or the Nth weekday of a month, a run each month or each week) and the runs of
 * particular years, which win over the rule for their WHOLE year.
 *
 * <p>A run of whole days runs from its first day's midnight to the midnight after its last, both days in; a
 * run with a time of day starts then and lasts its length, on the event's clock. A run belongs to the year it
 * STARTS in, and every run starts in its own year, so the year of a run's first instant, in the event's zone,
 * is the run's year. February 29th falls back to the 28th in a year without one ({@link MonthDay#atYear}).
 *
 * <p><b>A run's NUMBER is the year's rule's to give</b> ({@link YearRule#number}), never the other runs', and
 * the window never re-sorts or reassigns it:
 * <ul>
 *   <li>in a list of spans it is the span's authored position (its place in the list, from 1), so a span keeps
 *       its number wherever its days move;</li>
 *   <li>a monthly rule numbers a run by the month it starts in (December's is 12) and a weekly rule by its
 *       calendar week ({@link YearRule.Weekly#weekOfYear}: weeks from Monday, week 1 the one holding January
 *       1st), so adding or dropping months or weeks never renumbers the others, and a year's numbers may skip.
 *       Another day of the month keeps a monthly run's number; another weekday can carry a weekly run into a
 *       neighbouring week, and so to another number;</li>
 *   <li>a rule with one run a year numbers it 1.</li>
 * </ul>
 * A run crossing the new year is numbered by its start, in the year it starts in. Every question asked in time
 * ({@link #runContaining}, {@link #nextRun}, {@link #after}, {@link #forcedRun}) is answered by the dates, and the
 * run it finds keeps its number. Within a year, number order is date order for every rule but a list of spans
 * written out of date order.
 *
 * <p><b>Runs of one event never overlap.</b> A run that meets a run kept before it in that order is SET ASIDE
 * ({@link #setAside}): the run written first is kept, and the number of the run set aside is never handed to
 * another. Across the new year a year's runs are weighed against the year before's runs as that year dates
 * them, so the rule reads one year back and no further. A valid rule never meets itself
 * ({@link YearRule#valid}), so only spans, and the runs of particular years beside a rule, are ever set aside.
 * Nothing is dated before the floor (the event's FirstYear): a run that never happens sets nothing aside.
 *
 * <p><b>A year may skip runs by number</b> ({@link #of(YearRule, Map, int, Map)}), whichever rule dates it: a
 * skipped run is left out before any weighing, so it meets no other run, its number is never handed to another,
 * and the run after it is sought from its own days ({@link #after}). A number the year's runs never had skips
 * nothing ({@link #unknownSkips}).
 *
 * <p>A window made of per-year runs alone has no run in a year it does not list, and none after its last.
 * {@link #nextStartMs} is bounded by that last year, or by {@link #LAST_YEAR}, so it can never loop.
 *
 * <p>Pure: no clock, no store, no engine type; each year's runs are worked out once and kept. Not
 * {@code util/PeriodMath}: that answers fixed-length windows on the UTC grid, and a calendar window is calendar
 * arithmetic (month lengths, leap years, Easter, a zone's own midnight), so it is java.time's.
 */
public final class AnnualWindow {

    /** The last year a run can be dated in: the last four-digit year. */
    public static final int LAST_YEAR = 9999;

    /** No floor: the window dates every year its rule or its listed years name. */
    public static final int NO_FLOOR = Integer.MIN_VALUE;

    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{2})-(\\d{2})");

    /** Runs in time: by start. Kept runs never overlap, so this is also their order by end. */
    private static final Comparator<DatedRun> BY_START = Comparator.comparing(run -> run.days().start());

    /**
     * One run as the window dates it: its year, its number (from 1, as its year's rule gives it: a span's place,
     * a month, a calendar week), and its days.
     */
    public record DatedRun(int year, int number, @Nonnull RunDays days) {

        /** This run of {@code eventId}, its days counted in {@code zone}. */
        @Nonnull
        public Occurrence occurrence(@Nonnull String eventId, @Nonnull ZoneId zone) {
            return new Occurrence(eventId, year, number, days.startMs(zone), days.endMs(zone));
        }
    }

    /**
     * A run of {@code year} the window set aside: its {@code number}, which no other run takes, its days, and the
     * days of the run kept before it that it meets (one of the year before's, for a run crossing the new year).
     */
    public record SetAside(int year, int number, @Nonnull RunDays run, @Nonnull RunDays meets) {
    }

    /**
     * One year's runs kept, in number order; the ones set aside, in number order; and the ones its skip list leaves
     * out, in number order.
     */
    private record YearRuns(@Nonnull List<DatedRun> kept, @Nonnull List<SetAside> setAside,
                            @Nonnull List<DatedRun> skipped) {

        static final YearRuns NONE = new YearRuns(List.of(), List.of(), List.of());
    }

    @Nullable private final YearRule every;
    @Nonnull private final NavigableMap<Integer, YearRule> years;
    private final int floor;
    /** The run numbers each year leaves out, whichever rule dates it; a year skipping none is absent. */
    @Nonnull private final Map<Integer, Set<Integer>> skips;
    /** Each year's runs as its own layer dates them, a run meeting one kept before it in that year set aside. */
    private final Map<Integer, YearRuns> dated = new ConcurrentHashMap<>();
    /** Each year's runs as the window keeps them. */
    private final Map<Integer, YearRuns> kept = new ConcurrentHashMap<>();

    private AnnualWindow(@Nullable YearRule every, @Nonnull NavigableMap<Integer, YearRule> years, int floor,
            @Nonnull Map<Integer, Set<Integer>> skips) {
        this.every = every;
        this.years = years;
        this.floor = floor;
        this.skips = skips;
    }

    /** The every-year window from {@code first} to {@code last} ({@code MM-DD} each), or null when either is not a real day. */
    @Nullable
    public static AnnualWindow parse(@Nullable String first, @Nullable String last) {
        YearRule.Fixed fixed = fixed(first, last);
        return fixed == null ? null : new AnnualWindow(fixed, Collections.emptyNavigableMap(), NO_FLOOR, Map.of());
    }

    /** Fixed month-days from two {@code MM-DD} strings, or null when either is not a real day. */
    @Nullable
    public static YearRule.Fixed fixed(@Nullable String first, @Nullable String last) {
        MonthDay from = monthDay(first);
        MonthDay to = monthDay(last);
        return from == null || to == null ? null : new YearRule.Fixed(from, to);
    }

    /** {@link #of(YearRule, Map, int)} with no floor. */
    @Nullable
    public static AnnualWindow of(@Nullable YearRule every, @Nonnull Map<Integer, ? extends YearRule> years) {
        return of(every, years, NO_FLOOR);
    }

    /** {@link #of(YearRule, Map, int, Map)} with no run skipped. */
    @Nullable
    public static AnnualWindow of(@Nullable YearRule every, @Nonnull Map<Integer, ? extends YearRule> years,
            int floor) {
        return of(every, years, floor, Map.of());
    }

    /**
     * The window an every-year rule and the runs of particular years make, dating nothing before {@code floor}
     * (the event's FirstYear, or {@link #NO_FLOOR}); null when there is neither rule nor listed year. A listed
     * year's rule dates that year's whole runs, and {@code skips} leaves out runs of a year by number, whichever
     * rule dates it.
     *
     * @throws IllegalArgumentException for a rule, every-year or a listed year's, that is not {@link YearRule#valid()}
     */
    @Nullable
    public static AnnualWindow of(@Nullable YearRule every, @Nonnull Map<Integer, ? extends YearRule> years,
            int floor, @Nonnull Map<Integer, ? extends Collection<Integer>> skips) {
        if (every != null && !every.valid()) {
            throw new IllegalArgumentException("not a valid yearly rule: " + every.describe());
        }
        for (Map.Entry<Integer, ? extends YearRule> entry : years.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().valid()) {
                throw new IllegalArgumentException("not valid runs for " + entry.getKey());
            }
        }
        if (every == null && years.isEmpty()) {
            return null;
        }
        Map<Integer, Set<Integer>> skipped = new TreeMap<>();
        for (Map.Entry<Integer, ? extends Collection<Integer>> entry : skips.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                skipped.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
        }
        return new AnnualWindow(every, Collections.unmodifiableNavigableMap(new TreeMap<Integer, YearRule>(years)),
                floor, Collections.unmodifiableMap(skipped));
    }

    /** {@code MM-DD} as a month-day, or null for anything else ({@code 1-5}, {@code 02-30}, {@code 13-01}). */
    @Nullable
    public static MonthDay monthDay(@Nullable String text) {
        if (text == null) {
            return null;
        }
        Matcher m = MONTH_DAY.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            return MonthDay.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** The zone a {@code Clock} names: UTC when unauthored, null when java.time knows no such zone. */
    @Nullable
    public static ZoneId zone(@Nullable String clock) {
        if (clock == null || clock.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(clock.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** The calendar year {@code nowMs} falls in, in {@code zone}. */
    public static int yearOf(long nowMs, @Nonnull ZoneId zone) {
        return Instant.ofEpochMilli(nowMs).atZone(zone).getYear();
    }

    /** The runs that start in {@code year}, in number order; empty when the window dates none that year. */
    @Nonnull
    public List<RunDays> runs(int year) {
        return keptIn(year).kept().stream().map(DatedRun::days).toList();
    }

    /**
     * The runs that start in {@code year} with their numbers, in number order; empty when the window dates none
     * that year. A number set aside or skipped is missing, never taken by the run after it.
     */
    @Nonnull
    public List<DatedRun> datedRuns(int year) {
        return keptIn(year).kept();
    }

    /** The runs of {@code year} set aside, in number order, each because it meets a run kept before it. */
    @Nonnull
    public List<SetAside> setAside(int year) {
        return keptIn(year).setAside();
    }

    /** Run {@code number} of {@code year}, or null when the year has no such run (or set it aside, or skips it). */
    @Nullable
    public RunDays run(int year, int number) {
        DatedRun found = dated(year, number);
        return found == null ? null : found.days();
    }

    /** The year's first run (the lowest-numbered still standing, run 1 unless set aside), or null when it has none. */
    @Nullable
    public RunDays days(int year) {
        List<DatedRun> runs = datedRuns(year);
        return runs.isEmpty() ? null : runs.get(0).days();
    }

    /** Does the window date any run that starts in {@code year}? */
    public boolean hasRun(int year) {
        return !datedRuns(year).isEmpty();
    }

    /**
     * Do the days differ from one year to the next: a moving rule (Easter, a weekday), any per-year runs, or a year
     * that skips some?
     */
    public boolean moves() {
        return !years.isEmpty() || !skips.isEmpty() || (every != null && every.moves());
    }

    /**
     * The numbers {@code year}'s skip list names that its runs never had (a month or week its rule does not run
     * in, a place past the end of its list), in order: such a skip leaves nothing out. Empty when it skips none.
     */
    @Nonnull
    public List<Integer> unknownSkips(int year) {
        Set<Integer> skip = skips.getOrDefault(year, Set.of());
        if (skip.isEmpty()) {
            return List.of();
        }
        List<Integer> left = keptIn(year).skipped().stream().map(DatedRun::number).toList();
        return skip.stream().filter(number -> !left.contains(number)).sorted().toList();
    }

    /** Can the window date more than one run in a year: its rule, or the runs of any listed year? */
    public boolean several() {
        return (every != null && every.several()) || years.values().stream().anyMatch(YearRule::several);
    }

    /** Does the every-year rule cross the new year (fixed month-days whose last comes before its first)? */
    public boolean crossesNewYear() {
        return every instanceof YearRule.Fixed fixed && fixed.crossesNewYear();
    }

    /**
     * The first instant of the year's first run ({@link #days(int)}).
     *
     * @throws IllegalArgumentException when the window dates no run that year ({@link #hasRun})
     */
    public long startMs(int year, @Nonnull ZoneId zone) {
        return firstOf(year).startMs(zone);
    }

    /**
     * The first instant AFTER the year's first run ({@link #days(int)}).
     *
     * @throws IllegalArgumentException when the window dates no run that year ({@link #hasRun})
     */
    public long endMs(int year, @Nonnull ZoneId zone) {
        return firstOf(year).endMs(zone);
    }

    /**
     * The first instant of run {@code number} of {@code year}.
     *
     * @throws IllegalArgumentException when the year has no such run
     */
    public long startMs(int year, int number, @Nonnull ZoneId zone) {
        return required(year, number).startMs(zone);
    }

    /**
     * The first instant after run {@code number} of {@code year}.
     *
     * @throws IllegalArgumentException when the year has no such run
     */
    public long endMs(int year, int number, @Nonnull ZoneId zone) {
        return required(year, number).endMs(zone);
    }

    /** The run going on at {@code nowMs}, or null when none is. A run of the year before crossing the new year is found too. */
    @Nullable
    public DatedRun runContaining(long nowMs, @Nonnull ZoneId zone) {
        int year = yearOf(nowMs, zone);
        DatedRun found = containing(year, nowMs, zone);
        // A run crossing the new year from the year before: no run reaches two years past the one it starts in.
        return found != null ? found : containing(year - 1, nowMs, zone);
    }

    /** The year of the run going on at {@code nowMs}, or null when none is. */
    @Nullable
    public Integer yearContaining(long nowMs, @Nonnull ZoneId zone) {
        DatedRun run = runContaining(nowMs, zone);
        return run == null ? null : run.year();
    }

    /**
     * The first year from {@code fromYear} on that has a run, or null when none is left: under an every-year rule
     * the first year from it the window dates any run in, else the first listed year from it that does.
     */
    @Nullable
    public Integer nextRunYear(int fromYear) {
        for (Integer year = candidate(fromYear); year != null; year = candidate(year + 1)) {
            if (hasRun(year)) {
                return year;
            }
        }
        return null;
    }

    /**
     * The first run to start strictly after {@code nowMs} by the dates, never in a year before {@code fromYear};
     * null when none is left. Bounded: each step moves to a later year with a run, and runs start in their own year.
     */
    @Nullable
    public DatedRun nextRun(long nowMs, @Nonnull ZoneId zone, int fromYear) {
        Integer year = nextRunYear(Math.max(fromYear, yearOf(nowMs, zone) - 1));
        while (year != null) {
            DatedRun soonest = null;
            for (DatedRun run : datedRuns(year)) {
                if (run.days().startMs(zone) > nowMs && (soonest == null || BY_START.compare(run, soonest) < 0)) {
                    soonest = run;
                }
            }
            if (soonest != null) {
                return soonest;
            }
            year = year >= LAST_YEAR ? null : nextRunYear(year + 1);
        }
        return null;
    }

    /** The first run start strictly after {@code nowMs}, never in a year before {@code fromYear}; null when no run is left. */
    @Nullable
    public Long nextStartMs(long nowMs, @Nonnull ZoneId zone, int fromYear) {
        DatedRun next = nextRun(nowMs, zone, fromYear);
        return next == null ? null : next.days().startMs(zone);
    }

    /**
     * The run after run {@code number} of {@code year} by the dates: the same year's run starting soonest after
     * it, else the earliest run of the next year that has one; null when none is left. A run the year set aside
     * or skipped is followed from its own days. A number the year does not have is followed from where the year's
     * rule puts it ({@link YearRule#numberStart}: the start of that month, or of that calendar week), so a run
     * whose month an owner dropped is followed by the year's next run; a list of spans names no date for a place
     * it lacks, so that is followed by the next year's earliest run.
     */
    @Nullable
    public DatedRun after(int year, int number) {
        LocalDateTime from = startOf(year, number);
        if (from != null) {
            for (DatedRun run : inTime(year)) {
                if (run.days().start().isAfter(from)) {
                    return run;
                }
            }
        }
        Integer next = year >= LAST_YEAR ? null : nextRunYear(year + 1);
        return next == null ? null : inTime(next).get(0);
    }

    /**
     * Where run {@code number} of {@code year} starts, to seek the run after it: its own start, kept, set aside
     * or skipped, else where the year's rule puts that number; null when nothing dates it.
     */
    @Nullable
    private LocalDateTime startOf(int year, int number) {
        DatedRun kept = dated(year, number);
        if (kept != null) {
            return kept.days().start();
        }
        for (SetAside aside : setAside(year)) {
            if (aside.number() == number) {
                return aside.run().start();
            }
        }
        for (DatedRun skipped : keptIn(year).skipped()) {
            if (skipped.number() == number) {
                return skipped.days().start();
            }
        }
        YearRule rule = layer(year);
        return rule == null ? null : rule.numberStart(year, number);
    }

    /**
     * The run a force runs in {@code year}: by the dates, its first run not yet over at {@code nowMs}, else its
     * last; null when the window dates none that year. A force so brings the year's next run forward, and never
     * runs again a run that is over while a later one is still to come. The run keeps its number.
     */
    @Nullable
    public DatedRun forcedRun(int year, long nowMs, @Nonnull ZoneId zone) {
        List<DatedRun> runs = inTime(year);
        if (runs.isEmpty()) {
            return null;
        }
        for (DatedRun run : runs) {
            if (run.days().endMs(zone) > nowMs) {
                return run;
            }
        }
        return runs.get(runs.size() - 1);
    }

    /** The run of {@code year} going on at {@code nowMs}; kept runs never overlap, so at most one is. */
    @Nullable
    private DatedRun containing(int year, long nowMs, @Nonnull ZoneId zone) {
        for (DatedRun run : datedRuns(year)) {
            if (nowMs >= run.days().startMs(zone) && nowMs < run.days().endMs(zone)) {
                return run;
            }
        }
        return null;
    }

    /** Run {@code number} of {@code year} as kept, or null. */
    @Nullable
    private DatedRun dated(int year, int number) {
        for (DatedRun run : datedRuns(year)) {
            if (run.number() == number) {
                return run;
            }
        }
        return null;
    }

    /** {@code year}'s kept runs in time, the earliest first. */
    @Nonnull
    private List<DatedRun> inTime(int year) {
        List<DatedRun> runs = new ArrayList<>(datedRuns(year));
        runs.sort(BY_START);
        return runs;
    }

    /** The next year from {@code fromYear} that could have a run: any year under an every-year rule, else a listed one. */
    @Nullable
    private Integer candidate(int fromYear) {
        if (fromYear > LAST_YEAR) {
            return null;
        }
        return every != null ? Integer.valueOf(fromYear) : years.ceilingKey(fromYear);
    }

    /** The rule that dates {@code year}: its listed runs, else the every-year rule; null before the floor and after {@link #LAST_YEAR}. */
    @Nullable
    private YearRule layer(int year) {
        if (year < floor || year > LAST_YEAR) {
            return null;
        }
        YearRule listed = years.get(year);
        return listed != null ? listed : every;
    }

    /**
     * {@code year}'s runs as its own layer dates them, each numbered by that layer's rule ({@link YearRule#number}),
     * the numbers the year skips left out first, then a run meeting one kept before it in the order the rule gives
     * them set aside.
     */
    @Nonnull
    private YearRuns datedIn(int year) {
        YearRuns known = dated.get(year);
        if (known != null) {
            return known;
        }
        YearRule rule = layer(year);
        YearRuns made = YearRuns.NONE;
        if (rule != null) {
            Set<Integer> skip = skips.getOrDefault(year, Set.of());
            List<RunDays> authored = rule.runs(year);
            List<DatedRun> runs = new ArrayList<>();
            List<SetAside> aside = new ArrayList<>();
            List<DatedRun> skipped = new ArrayList<>();
            for (int i = 0; i < authored.size(); i++) {
                RunDays run = authored.get(i);
                int number = rule.number(run, i + 1);
                if (skip.contains(number)) {
                    // Left out before any weighing: a run that never happens meets no other.
                    skipped.add(new DatedRun(year, number, run));
                    continue;
                }
                DatedRun met = firstMet(runs, run);
                if (met != null) {
                    aside.add(new SetAside(year, number, run, met.days()));
                } else {
                    runs.add(new DatedRun(year, number, run));
                }
            }
            made = new YearRuns(List.copyOf(runs), List.copyOf(aside), List.copyOf(skipped));
        }
        dated.putIfAbsent(year, made);
        return made;
    }

    /** {@code year}'s runs as kept: as dated, less any that meets one of the year before's runs, as that year dates them. */
    @Nonnull
    private YearRuns keptIn(int year) {
        YearRuns known = kept.get(year);
        if (known != null) {
            return known;
        }
        YearRuns own = datedIn(year);
        YearRuns made = own;
        List<DatedRun> reaching = own.kept().isEmpty() || year <= floor ? List.of() : reachingInto(year);
        if (!reaching.isEmpty()) {
            List<DatedRun> runs = new ArrayList<>();
            List<SetAside> aside = new ArrayList<>(own.setAside());
            for (DatedRun run : own.kept()) {
                DatedRun met = firstMet(reaching, run.days());
                if (met != null) {
                    aside.add(new SetAside(year, run.number(), run.days(), met.days()));
                } else {
                    runs.add(run);
                }
            }
            aside.sort(Comparator.comparingInt(SetAside::number));
            made = new YearRuns(List.copyOf(runs), List.copyOf(aside), own.skipped());
        }
        kept.putIfAbsent(year, made);
        return made;
    }

    /** The year before's runs, as that year dates them, that end after {@code year} begins. */
    @Nonnull
    private List<DatedRun> reachingInto(int year) {
        LocalDateTime newYear = LocalDateTime.of(year, 1, 1, 0, 0);
        List<DatedRun> out = new ArrayList<>();
        for (DatedRun run : datedIn(year - 1).kept()) {
            if (run.days().end().isAfter(newYear)) {
                out.add(run);
            }
        }
        return out;
    }

    /** The first of {@code runs} that {@code run} meets, or null when it meets none. */
    @Nullable
    private static DatedRun firstMet(@Nonnull List<DatedRun> runs, @Nonnull RunDays run) {
        for (DatedRun other : runs) {
            if (run.meets(other.days())) {
                return other;
            }
        }
        return null;
    }

    @Nonnull
    private RunDays firstOf(int year) {
        RunDays run = days(year);
        if (run == null) {
            throw new IllegalArgumentException("the window dates no run in " + year);
        }
        return run;
    }

    @Nonnull
    private RunDays required(int year, int number) {
        RunDays run = run(year, number);
        if (run == null) {
            throw new IllegalArgumentException("the window dates no run " + number + " in " + year);
        }
        return run;
    }

    /** The rule's own data form ({@code MM-DD..MM-DD} for fixed days), then any dated years. */
    @Override
    @Nonnull
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (every != null) {
            parts.add(every.describe());
        }
        if (!years.isEmpty()) {
            parts.add("Years " + String.join(", ", years.keySet().stream().map(String::valueOf).toList()));
        }
        return String.join(" + ", parts);
    }
}
