package com.ziggfreed.common.calendar.asset;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.calendar.AnnualWindow;
import com.ziggfreed.common.calendar.LiveRule;
import com.ziggfreed.common.calendar.YearRule;
import com.ziggfreed.common.calendar.moon.MoonRange;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.season.SeasonGate;

/**
 * One event that comes round every year, at {@code Server/ZiggfreedCommon/CalendarEvents/<Owner>/<Id>.json}.
 * The FILE NAME is the id and the folders are the author's own grouping, so two packs' files of one name
 * are one event: start the name with your content's own word.
 *
 * <pre>{@code
 * { "Window": { "Start": "10-01", "End": "11-03" },
 *   "FirstYear": 2026,
 *   "Clock": "UTC",
 *   "Presentation": { "TitleKey": "calendar.Spring_Fair.name" },
 *   "Herald": { "Start": { "TitleKey": "calendar.Spring_Fair.herald.start", "Major": true },
 *               "End":   { "TitleKey": "calendar.Spring_Fair.herald.end" } } }
 * }</pre>
 *
 * <p><b>Both days of the {@code Window} are in it</b>: the event above runs from the first instant of
 * October 1st to the last instant of November 3rd, counted in the {@code Clock} zone. An {@code End}
 * before its {@code Start} crosses the new year, and a run belongs to the year it starts in.
 *
 * <p><b>Days that move</b>: a {@code Rule} works each year's runs out, around Easter Sunday
 * ({@code {"Type": "Easter", "Before": 10, "After": 7}}), the Nth weekday of a month
 * ({@code {"Type": "Weekday", "Month": 11, "Weekday": "Thursday", "Nth": 4}}), each month
 * ({@code {"Type": "Monthly", "Weekday": "Sunday", "Nth": 1, "Days": 7}}) or each week
 * ({@code {"Type": "Weekly", "Weekday": "Sunday", "At": "14:00", "Length": "PT2H"}}), and {@code Years} sets the
 * days of particular years ({@code {"2031": {"Start": "04-01", "End": "04-20"}}}). Years win over a Rule,
 * a Rule over Start and End, and every run must start in its own year and stay clear of the next. A year may
 * hold several runs: a Fixed Rule's {@code Runs}, or a Years entry's, list spans, each numbered by its place in
 * the list wherever its days move; a monthly rule numbers each by its month (December's is run 12) and a weekly
 * rule by its calendar week (Monday to Sunday, week 1 the one holding January 1st), so adding or dropping months
 * or weeks never renumbers the others. Another day of the month keeps a monthly run's number; another weekday can
 * move a weekly run into a neighbouring week, and so change its number. A run meeting one before it is set aside
 * ({@link #setAside()}), and a Years entry's {@code Skip} leaves runs out by number; neither number goes to
 * another run.
 *
 * <p><b>Live by a condition instead of dates</b>: {@code "Moon": {"MoonPhaseRange": [0, 1], "World": ""}} is live
 * while a world's moon is in those phases (the engine's own key and wrap rule; phase 0 is full; a blank World is the
 * default world), and {@code "Live": {"AnyOf": [...], "AllOf": [...], "NoneOf": [...]}} is live by other events, so
 * content keeps one Season string. Live wins over Moon, and either over a Window. Such an event is a condition, not
 * a run: it declares {@code <Id>} and {@code <Id>_Live} like any event, so every Season leaf and
 * {@code calendar_live} read it, but it has no runs, so nothing that counts runs (attendance, history, a yearly
 * copy, the Almanac, a stat's season line, a spawn rule) ever sees it. It needs no FirstYear. Its Herald shows the
 * start banner at each rise and the end banner as it sets, or each at most once a day ({@code Herald.OncePerDay}).
 *
 * <p><b>{@code Enabled: false} makes the event ABSENT, not locked</b>: content gated on it vanishes,
 * as it does when the server owner switches every event off ({@code mods/ziggfreedcommon/calendar.json},
 * {@code "$Enabled": false}). An event with no readable {@code Window}, or no {@code FirstYear} from 1970
 * to 9999, never runs, and the server log says why.
 *
 * <p><b>Three ids are not an event's to take</b>, because an event's switches are features in the same
 * namespace as two others: {@code Calendar} (the owner's switch over every event), {@code Almanac} (the
 * Almanac's switch) and any id ending in {@code _Live} (an event's running switch is its id plus
 * {@code _Live}). A file under one of them never runs, and the server log says why. Nor does a file whose
 * name carries {@code |} or {@code @}, which a player's attendance record cannot save.
 */
public final class CalendarEventAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, CalendarEventAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/CalendarEvents";

    /** The id is one a feature switch in the calendar's namespace already uses; the event never runs. */
    public static final String PROBLEM_ID_RESERVED = "ID_RESERVED";
    /** The id carries {@code |} or {@code @}, which a player's attendance record cannot save; the event never runs. */
    public static final String PROBLEM_ID_UNSAVABLE = "ID_UNSAVABLE";
    /** The file states no Window at all. */
    public static final String PROBLEM_WINDOW_MISSING = "WINDOW_MISSING";
    /**
     * A Start or End that is not a real MM-DD day, a Fixed Rule whose Runs is empty, holds such a span or holds more
     * spans than a run's number can name (54, zc-core's {@code Occurrence.MAX_NUMBER}), or a Rule missing a leaf it
     * needs; the event never runs.
     */
    public static final String PROBLEM_WINDOW_UNREADABLE = "WINDOW_UNREADABLE";
    /** The file states no FirstYear. */
    public static final String PROBLEM_FIRST_YEAR_MISSING = "FIRST_YEAR_MISSING";
    /** A FirstYear before 1970 or after 9999; the event never runs. */
    public static final String PROBLEM_FIRST_YEAR_OUT_OF_RANGE = "FIRST_YEAR_OUT_OF_RANGE";
    /** A Clock java.time does not know; the event runs on UTC. */
    public static final String PROBLEM_CLOCK_UNKNOWN = "CLOCK_UNKNOWN";
    /** A Window whose runs could start in the year before or meet the rule's next run; the event never runs. */
    public static final String PROBLEM_WINDOW_RUN_INVALID = "WINDOW_RUN_INVALID";
    /**
     * A Years entry that is not a four-digit year from FirstYear on, or whose days (Start and End, or a span of its
     * Runs) are not MM-DD or run February 29th through February 28th, or whose Runs holds more than 54 spans; that
     * entry is not used.
     */
    public static final String PROBLEM_YEARS_ENTRY_IGNORED = "YEARS_ENTRY_IGNORED";
    /** Start and End beside a Rule, which wins over them: a note, never a problem. */
    public static final String NOTE_START_END_IGNORED = "START_END_IGNORED";
    /** A run that meets one before it, set aside; the event still runs ({@link #setAside()}). */
    public static final String PROBLEM_RUN_SET_ASIDE = "RUN_SET_ASIDE";
    /** Start and End beside Runs (in a Fixed Rule or a Years entry), which wins over them: a note, never a problem. */
    public static final String NOTE_START_END_BESIDE_RUNS = "START_END_BESIDE_RUNS";
    /** A Years entry's Skip names a run number its year does not have, so it skips nothing ({@link #unknownSkips()}). */
    public static final String PROBLEM_SKIP_UNKNOWN_RUN = "SKIP_UNKNOWN_RUN";
    /** A Moon whose MoonPhaseRange is not two phases of 0 or more; the event is never live. */
    public static final String PROBLEM_MOON_RANGE_INVALID = "MOON_RANGE_INVALID";
    /** A Live naming no event in AnyOf, AllOf or NoneOf; the event is never live. */
    public static final String PROBLEM_LIVE_EMPTY = "LIVE_EMPTY";
    /** A Window beside a Moon or a Live, which wins over it: a note, never a problem. */
    public static final String NOTE_WINDOW_UNUSED = "WINDOW_UNUSED";
    /** A Moon beside a Live, which wins over it: a note, never a problem. */
    public static final String NOTE_MOON_UNUSED = "MOON_UNUSED";

    /** How many years from FirstYear the audit reads for runs set aside: a leap year with both its edges among them. */
    static final int AUDIT_YEARS = 5;

    /** A Years key: exactly four digits. */
    private static final Pattern YEAR_KEY = Pattern.compile("\\d{4}");

    /** The earliest FirstYear an event may name: the year the epoch every instant here counts from begins. */
    static final int MIN_FIRST_YEAR = 1970;

    /** The latest FirstYear an event may name: the last four-digit year. */
    static final int MAX_FIRST_YEAR = AnnualWindow.LAST_YEAR;

    /**
     * The ids no event may take, lower-cased: the owner's switch over every event ({@code Calendar}) and
     * the Almanac's switch ({@code Almanac}), features in the same {@code ziggfreedcommon} namespace an
     * event's own switches are declared in.
     */
    private static final Set<String> RESERVED_IDS = Set.of("calendar", "almanac");

    /**
     * An event's running switch is its id plus {@code _Live} ({@link SeasonGate#LIVE_SUFFIX}), so no event id
     * may end in it. Lower-cased.
     */
    private static final String RESERVED_SUFFIX = SeasonGate.LIVE_SUFFIX.toLowerCase(Locale.ROOT);

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private Window window;
    @Nullable private Moon moon;
    @Nullable private Live live;
    @Nullable private Integer firstYear;
    @Nullable private String clock;
    @Nullable private Presentation presentation;
    @Nullable private Herald herald;

    /** The Window read once per decoded file. */
    @Nullable private volatile ParsedWindow parsedWindow;

    /** The Live group read once per decoded file. */
    @Nullable private volatile LiveRule parsedLive;

    public static final AssetBuilderCodec<String, CalendarEventAsset> CODEC = AssetBuilderCodec.builder(
                    CalendarEventAsset.class,
                    CalendarEventAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the event exists on this server at all; unauthored means true. False makes it "
                    + "absent rather than locked: everything gated on it vanishes until it is switched back on.")
            .add()
            .appendInherited(new KeyedCodec<>("Window", Window.CODEC, false),
                    (a, v) -> a.window = v, a -> a.window, (a, p) -> a.window = p.window)
            .documentation("The days the event runs each year. Start and End are MM-DD month-days with both days "
                    + "included, the same every year; a Rule (Fixed, Easter, Weekday, Monthly or Weekly) works each "
                    + "year's runs out instead and wins over them; Years sets the days of particular years and wins "
                    + "over both. An End before its Start crosses the new year, and that run belongs to the year it "
                    + "starts in. A Window naming only some leaves under Parent or in an owner entry keeps the rest. "
                    + "Not used when a Moon or a Live is authored.")
            .add()
            .appendInherited(new KeyedCodec<>("Moon", Moon.CODEC, false),
                    (a, v) -> a.moon = v, a -> a.moon, (a, p) -> a.moon = p.moon)
            .documentation("Live while a world's moon is in the phases named, instead of on dates: the game's own "
                    + "moon, as a world spawn's MoonPhaseRange reads it. Wins over a Window. Such an event has no runs, "
                    + "so nothing that counts runs (attendance, past runs, a yearly copy, the Almanac) sees it; content "
                    + "gated on its Season shows while it is live.")
            .add()
            .appendInherited(new KeyedCodec<>("Live", Live.CODEC, false),
                    (a, v) -> a.live = v, a -> a.live, (a, p) -> a.live = p.live)
            .documentation("Live by other calendar events instead of on dates: while any of AnyOf is live, all of "
                    + "AllOf are and none of NoneOf is, so content keeps one Season string. Wins over a Moon and a "
                    + "Window. An event whose Live leads back to itself is never live. Such an event has no runs.")
            .add()
            .appendInherited(new KeyedCodec<>("FirstYear", Codec.INTEGER, false),
                    (a, v) -> a.firstYear = v, a -> a.firstYear, (a, p) -> a.firstYear = p.firstYear)
            .documentation("The first year the event runs, from " + MIN_FIRST_YEAR + " to " + MAX_FIRST_YEAR
                    + ". Runs before it never happen, and a list of past runs starts here. Required: an event "
                    + "without one, or with one outside those years, never runs. Not used by an event with a Moon or "
                    + "a Live.")
            .add()
            .appendInherited(new KeyedCodec<>("Clock", Codec.STRING, false),
                    (a, v) -> a.clock = v, a -> a.clock, (a, p) -> a.clock = p.clock)
            .metadata(EditorSchema.defaultValue("UTC"))
            .documentation("The time zone the Window's days are counted in: a zone id (UTC, Europe/Paris, "
                    + "America/New_York) or an offset (+02:00), and for an event with a Moon or a Live the day "
                    + "Herald.OncePerDay counts by. Unauthored means UTC; a zone this server does not know also "
                    + "counts in UTC, with a warning.")
            .add()
            .appendInherited(new KeyedCodec<>("Presentation", Presentation.CODEC, false),
                    (a, v) -> a.presentation = v, a -> a.presentation, (a, p) -> a.presentation = p.presentation)
            .documentation("What the event is called and what stands for it wherever events are listed.")
            .add()
            .appendInherited(new KeyedCodec<>("Herald", Herald.CODEC, false),
                    (a, v) -> a.herald = v, a -> a.herald, (a, p) -> a.herald = p.herald)
            .documentation("The banner a player sees when a run begins for them and when it ends. Unauthored means "
                    + "the event comes and goes without one. Enabled false keeps every run quiet; FirstRunOfYear true "
                    + "shows the start banner on the year's first run and the end banner when its last run ends, both "
                    + "by their dates. An event with a Moon or a Live shows them at each rise and set instead, or "
                    + "each at most once a day with OncePerDay true.")
            .add()
            .build();

    public CalendarEventAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Is the event switched on in its own file? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public String windowStart() {
        return window == null ? null : window.start;
    }

    @Nullable
    public String windowEnd() {
        return window == null ? null : window.end;
    }

    /** The days the event runs year by year, or null when the file states none or none it can use. */
    @Nullable
    public AnnualWindow annualWindow() {
        return parsed().window();
    }

    /** What the file authors that is not used, as stable codes (INFO, never a problem); empty when nothing is. */
    @Nonnull
    public List<String> notes() {
        if (!isCondition()) {
            return parsed().notes();
        }
        List<String> out = new ArrayList<>();
        if (window != null && !window.isEmpty()) {
            out.add(NOTE_WINDOW_UNUSED);
        }
        if (followsEvents() && moon != null) {
            out.add(NOTE_MOON_UNUSED);
        }
        return List.copyOf(out);
    }

    /** The Window as read, or one that dates nothing for an event live by a condition (its Window is not used). */
    @Nonnull
    private ParsedWindow parsed() {
        ParsedWindow cached = parsedWindow;
        if (cached == null) {
            cached = isCondition() ? ParsedWindow.none(List.of(), List.of()) : readWindow(window, firstYear);
            parsedWindow = cached;
        }
        return cached;
    }

    /**
     * Is the event live by a condition, a {@code Moon} or a {@code Live}, rather than by its dates? Such an event has
     * no runs: {@link #annualWindow()} is null and {@link #canRun()} false, so every reader of runs reads it as
     * absent, and it follows its condition instead ({@link #canFollow()}).
     */
    public boolean isCondition() {
        return live != null || moon != null;
    }

    /** Is it live by other events: a {@code Live}, which wins over a Moon? */
    public boolean followsEvents() {
        return live != null;
    }

    /** Is it live by a world's moon: a {@code Moon} and no Live? */
    public boolean followsMoon() {
        return live == null && moon != null;
    }

    /** Can it follow its condition: an id of its own and a readable Moon or Live? False for an event on dates. */
    public boolean canFollow() {
        return isCondition() && !isReservedId(id) && conditionProblem() == null;
    }

    /** The MoonPhaseRange it follows, {@code [first, last]}, when it follows a readable Moon; else null. */
    @Nullable
    public int[] moonRange() {
        int[] range = moon == null ? null : moon.moonPhaseRange;
        return followsMoon() && MoonRange.readable(range) ? range.clone() : null;
    }

    /** The world whose moon it follows, trimmed; null for the default world, or when it follows no Moon. */
    @Nullable
    public String moonWorld() {
        return followsMoon() ? blankToNull(moon.world) : null;
    }

    /** The other events it is live by, when it has a Live; else null. */
    @Nullable
    public LiveRule liveRule() {
        if (live == null) {
            return null;
        }
        LiveRule cached = parsedLive;
        if (cached == null) {
            cached = LiveRule.of(live.anyOf, live.allOf, live.noneOf);
            parsedLive = cached;
        }
        return cached;
    }

    /** What stops a condition event being live, as a code; null when nothing does or it is an event on dates. */
    @Nullable
    private String conditionProblem() {
        if (followsEvents()) {
            LiveRule rule = liveRule();
            return rule == null || rule.isEmpty() ? PROBLEM_LIVE_EMPTY : null;
        }
        if (followsMoon()) {
            return MoonRange.readable(moon.moonPhaseRange) ? null : PROBLEM_MOON_RANGE_INVALID;
        }
        return null;
    }

    /**
     * The runs the Window sets aside because each meets a run before it, each named once (in the first year it
     * is); empty when none is. The audit reads {@link #AUDIT_YEARS} years from FirstYear and each listed year with
     * the year after it.
     */
    @Nonnull
    public List<AnnualWindow.SetAside> setAside() {
        return parsed().setAside();
    }

    /**
     * The run numbers each Years entry's Skip names that its year does not have, by year, each year's in order;
     * empty when every skip leaves a run out.
     */
    @Nonnull
    public Map<Integer, List<Integer>> unknownSkips() {
        return parsed().unknownSkips();
    }

    /**
     * The Window read once: the days it gives, what stops or trims it, what it authors that is unused, the runs it
     * sets aside and the skips that leave nothing out.
     */
    private record ParsedWindow(@Nullable AnnualWindow window, @Nonnull List<String> problems,
                                @Nonnull List<String> notes, @Nonnull List<AnnualWindow.SetAside> setAside,
                                @Nonnull Map<Integer, List<Integer>> unknownSkips) {

        /** A Window that dates nothing, for {@code problems}. */
        @Nonnull
        static ParsedWindow none(@Nonnull List<String> problems, @Nonnull List<String> notes) {
            return new ParsedWindow(null, List.copyOf(problems), List.copyOf(notes), List.of(), Map.of());
        }
    }

    /**
     * Years win over a Rule, a Rule over Start and End. A Rule, or Start and End, that cannot be read, or whose
     * runs could start in the year before or meet the next run, stops the event; a bad Years entry costs that
     * entry alone, and a Years entry of Skip alone leaves those runs out of what the rest dates. Nothing is dated
     * before a FirstYear from 1970 to 9999.
     */
    @Nonnull
    private static ParsedWindow readWindow(@Nullable Window authored, @Nullable Integer firstYear) {
        if (authored == null || authored.isEmpty()) {
            return ParsedWindow.none(List.of(PROBLEM_WINDOW_MISSING), List.of());
        }
        List<String> notes = new ArrayList<>();
        YearRule every = null;
        if (authored.rule != null) {
            if (authored.start != null || authored.end != null) {
                notes.add(NOTE_START_END_IGNORED);
            }
            if (authored.rule instanceof WindowRules.Fixed fixed && fixed.runsBesideDays()) {
                notes.add(NOTE_START_END_BESIDE_RUNS);
            }
            every = authored.rule.toYearRule();
            if (every == null) {
                return ParsedWindow.none(List.of(PROBLEM_WINDOW_UNREADABLE), notes);
            }
        } else if (authored.start != null || authored.end != null) {
            every = AnnualWindow.fixed(authored.start, authored.end);
            if (every == null) {
                return ParsedWindow.none(List.of(PROBLEM_WINDOW_UNREADABLE), List.of());
            }
        }
        if (every != null && !every.valid()) {
            // Start and End as well as a Rule: February 29th through February 28th meets its own next run.
            return ParsedWindow.none(List.of(PROBLEM_WINDOW_RUN_INVALID), notes);
        }
        Map<Integer, YearRule> years = new TreeMap<>();
        Map<Integer, Set<Integer>> skips = new TreeMap<>();
        boolean ignored = false;
        boolean beside = false;
        for (Map.Entry<String, WindowRules.YearDays> entry : authored.yearsOrEmpty().entrySet()) {
            Integer year = yearKey(entry.getKey());
            WindowRules.YearDays value = entry.getValue();
            if (year == null || value == null || (firstYear != null && year < firstYear)) {
                ignored = true;
                continue;
            }
            if (!value.skipsOnly()) {
                YearRule days = value.toYearRule();
                if (days == null || !days.valid()) {
                    ignored = true;
                    continue;
                }
                years.put(year, days);
                beside |= value.runsBesideDays();
            }
            if (!value.skipped().isEmpty()) {
                skips.put(year, value.skipped());
            }
        }
        if (beside && !notes.contains(NOTE_START_END_BESIDE_RUNS)) {
            notes.add(NOTE_START_END_BESIDE_RUNS);
        }
        int floor = firstYear != null && isFirstYearInRange(firstYear) ? firstYear : AnnualWindow.NO_FLOOR;
        AnnualWindow parsed = AnnualWindow.of(every, years, floor, skips);
        List<String> problems = new ArrayList<>();
        List<AnnualWindow.SetAside> setAside = List.of();
        Map<Integer, List<Integer>> unknownSkips = Map.of();
        if (parsed == null) {
            problems.add(PROBLEM_WINDOW_UNREADABLE);
        } else {
            // A year that skips runs weighs the rest afresh, so it is read like a listed year.
            Set<Integer> ownDays = new TreeSet<>(years.keySet());
            ownDays.addAll(skips.keySet());
            setAside = setAsideIn(parsed, floor, ownDays);
            unknownSkips = unknownSkipsIn(parsed, skips.keySet());
        }
        if (ignored) {
            problems.add(PROBLEM_YEARS_ENTRY_IGNORED);
        }
        if (!setAside.isEmpty()) {
            problems.add(PROBLEM_RUN_SET_ASIDE);
        }
        if (!unknownSkips.isEmpty()) {
            problems.add(PROBLEM_SKIP_UNKNOWN_RUN);
        }
        return new ParsedWindow(parsed, List.copyOf(problems), List.copyOf(notes), setAside, unknownSkips);
    }

    /**
     * The runs {@code window} sets aside, each named once: in the {@link #AUDIT_YEARS} years from {@code floor} (a
     * leap year and both its edges among them, so every span's yearly meeting shows), and in each {@code listed}
     * year (one a Years entry dates or skips runs of) and the year after it. A year the every-year rule dates
     * against itself sets the same runs aside each year, so there a run is named by its number in the first year it
     * is; a listed year's, and the next year's, by year and number.
     */
    @Nonnull
    private static List<AnnualWindow.SetAside> setAsideIn(@Nonnull AnnualWindow window, int floor,
            @Nonnull Set<Integer> listed) {
        SortedSet<Integer> years = new TreeSet<>();
        if (floor != AnnualWindow.NO_FLOOR) {
            for (int year = floor; year < floor + AUDIT_YEARS && year <= MAX_FIRST_YEAR; year++) {
                years.add(year);
            }
        }
        for (int year : listed) {
            years.add(year);
            if (year < MAX_FIRST_YEAR) {
                years.add(year + 1);
            }
        }
        List<AnnualWindow.SetAside> out = new ArrayList<>();
        Set<String> named = new HashSet<>();
        for (int year : years) {
            boolean ownYear = listed.contains(year) || listed.contains(year - 1);
            for (AnnualWindow.SetAside aside : window.setAside(year)) {
                if (named.add(ownYear ? year + "#" + aside.number() : "#" + aside.number())) {
                    out.add(aside);
                }
            }
        }
        return List.copyOf(out);
    }

    /** The numbers each of {@code years} skips that its runs never had, by year; years that name none are absent. */
    @Nonnull
    private static Map<Integer, List<Integer>> unknownSkipsIn(@Nonnull AnnualWindow window,
            @Nonnull Set<Integer> years) {
        Map<Integer, List<Integer>> out = new TreeMap<>();
        for (int year : years) {
            List<Integer> unknown = window.unknownSkips(year);
            if (!unknown.isEmpty()) {
                out.put(year, unknown);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** A Years key as its year: four digits, 1970 or later; null for anything else. */
    @Nullable
    private static Integer yearKey(@Nullable String key) {
        if (key == null || !YEAR_KEY.matcher(key.trim()).matches()) {
            return null;
        }
        int year = Integer.parseInt(key.trim());
        return year >= MIN_FIRST_YEAR ? year : null;
    }

    @Nullable
    public Integer firstYear() {
        return firstYear;
    }

    /** The zone the days are counted in: the Clock's, or UTC when it states none or one nobody knows. */
    @Nonnull
    public ZoneId zone() {
        ZoneId zone = AnnualWindow.zone(clock);
        return zone == null ? ZoneOffset.UTC : zone;
    }

    @Nullable
    public Presentation presentation() {
        return presentation;
    }

    @Nullable
    public HeraldLine heraldStart() {
        return herald == null ? null : herald.start;
    }

    @Nullable
    public HeraldLine heraldEnd() {
        return herald == null ? null : herald.end;
    }

    /**
     * Does the event show its start banner for run {@code number} of {@code year}: its Herald switched on (unsaid is
     * on), and every run, or only the year's first by its dates when it says FirstRunOfYear?
     */
    public boolean heraldShowsStart(int year, int number) {
        return heraldShows(year, number, true);
    }

    /**
     * Does the event show its end banner when run {@code number} of {@code year} ends: its Herald switched on (unsaid
     * is on), and every run, or only the year's last by its dates when it says FirstRunOfYear?
     */
    public boolean heraldShowsEnd(int year, int number) {
        return heraldShows(year, number, false);
    }

    /** Is its Herald switched on (unsaid is on)? What an event live by a condition asks before each banner. */
    public boolean heraldOn() {
        return herald == null || !Boolean.FALSE.equals(herald.enabled);
    }

    /**
     * Does an event live by a condition show each of its banners to a player at most once a day, in its Clock,
     * rather than at every rise and set? Unsaid is false.
     */
    public boolean heraldOncePerDay() {
        return herald != null && Boolean.TRUE.equals(herald.oncePerDay);
    }

    /**
     * The two answers' one rule: the start banner reads the year's first run, the end banner its last. Both are the
     * year's by their dates, never by number: a number is the year's rule's name for a run (a month, a calendar week,
     * a span's place), so a weekly year can begin at week 2 and a list of spans can be written out of date order.
     */
    private boolean heraldShows(int year, int number, boolean start) {
        if (herald == null) {
            return true;
        }
        if (Boolean.FALSE.equals(herald.enabled)) {
            return false;
        }
        return !Boolean.TRUE.equals(herald.firstRunOfYear) || edgeRunOfYear(year, start) == number;
    }

    /**
     * The number of {@code year}'s first run by start ({@code first}), else of its last: kept runs never overlap, so
     * the last to start is the last to end. A year the window dates no run for reads run 1 as both, as a year of one
     * run would.
     */
    private int edgeRunOfYear(int year, boolean first) {
        AnnualWindow window = annualWindow();
        AnnualWindow.DatedRun edge = null;
        if (window != null) {
            for (AnnualWindow.DatedRun run : window.datedRuns(year)) {
                LocalDateTime start = run.days().start();
                LocalDateTime best = edge == null ? null : edge.days().start();
                if (best == null || (first ? start.isBefore(best) : start.isAfter(best))) {
                    edge = run;
                }
            }
        }
        return edge == null ? 1 : edge.number();
    }

    /**
     * Can the event run at all: an id of its own, a readable Window and a FirstYear from 1970 to 9999? Never for an
     * event live by a condition, which has no runs ({@link #canFollow()}).
     */
    public boolean canRun() {
        return !isReservedId(id) && annualWindow() != null && firstYear != null && isFirstYearInRange(firstYear);
    }

    /**
     * Is {@code year} one a FirstYear may name, 1970 to 9999? A year far enough outside them throws from the
     * window maths, and a far-past one would have the list of past runs count every year since.
     */
    private static boolean isFirstYearInRange(int year) {
        return year >= MIN_FIRST_YEAR && year <= MAX_FIRST_YEAR;
    }

    /**
     * Is {@code id} one no event may take? {@code Calendar} and {@code Almanac} name other switches in the
     * feature namespace an event's switches share, an id ending in {@code _Live} would collide with
     * another event's running switch, and an id carrying {@code |} or {@code @} could never be saved in a
     * player's attendance record ({@link #carriesAttendanceSeparator}). Matched without regard to case.
     */
    public static boolean isReservedId(@Nullable String id) {
        return namesAnotherSwitch(id) || carriesAttendanceSeparator(id);
    }

    /**
     * Does {@code id} carry {@code |} or {@code @}? A player's attendance record saves one
     * {@code <eventid>@<year>} entry per run, joined with {@code |}, so it reserves both: such an event could
     * never be credited, fire its attendance or show its start banner.
     */
    public static boolean carriesAttendanceSeparator(@Nullable String id) {
        return id != null && (id.indexOf('|') >= 0 || id.indexOf('@') >= 0);
    }

    /** Is {@code id} {@code Calendar}, {@code Almanac} or one ending in {@code _Live}, without regard to case? */
    private static boolean namesAnotherSwitch(@Nullable String id) {
        if (id == null) {
            return false;
        }
        String folded = id.trim().toLowerCase(Locale.ROOT);
        return RESERVED_IDS.contains(folded) || folded.endsWith(RESERVED_SUFFIX);
    }

    /** What is wrong with this file, as stable codes in a fixed order; empty when nothing is. */
    @Nonnull
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (namesAnotherSwitch(id)) {
            out.add(PROBLEM_ID_RESERVED);
        }
        if (carriesAttendanceSeparator(id)) {
            out.add(PROBLEM_ID_UNSAVABLE);
        }
        if (isCondition()) {
            // Live by a condition: no Window or FirstYear is needed, only a readable Moon or Live.
            String problem = conditionProblem();
            if (problem != null) {
                out.add(problem);
            }
        } else {
            out.addAll(parsed().problems());
            if (firstYear == null) {
                out.add(PROBLEM_FIRST_YEAR_MISSING);
            } else if (!isFirstYearInRange(firstYear)) {
                out.add(PROBLEM_FIRST_YEAR_OUT_OF_RANGE);
            }
        }
        if (AnnualWindow.zone(clock) == null) {
            out.add(PROBLEM_CLOCK_UNKNOWN);
        }
        return List.copyOf(out);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The Window group. {@code Start} and {@code End} are the same month-days every year; a {@code Rule}
     * works each year's days out and wins over them ({@link WindowRules}); {@code Years} sets the days of
     * particular years, or skips some of their runs, and wins over both: each entry is a
     * {@link WindowRules.YearDays}, never a Rule shape. Years merge by year, and an entry leaf by leaf, under
     * {@code Parent} and in an owner entry.
     */
    public static final class Window {

        private static final InheritMapCodec<WindowRules.YearDays> YEARS_CODEC =
                new InheritMapCodec<>(WindowRules.YearDays.CODEC);

        @Nullable private String start;
        @Nullable private String end;
        @Nullable private WindowRules.Rule rule;
        @Nullable private Map<String, WindowRules.YearDays> years;

        public static final BuilderCodec<Window> CODEC = BuilderCodec.builder(Window.class, Window::new)
                .appendInherited(new KeyedCodec<>("Start", Codec.STRING, false),
                        (o, v) -> o.start = v, o -> o.start, (o, p) -> o.start = p.start)
                .documentation("The first day of the event, as MM-DD (10-01 is October 1st), the same every "
                        + "year. Not used when a Rule is authored.").add()
                .appendInherited(new KeyedCodec<>("End", Codec.STRING, false),
                        (o, v) -> o.end = v, o -> o.end, (o, p) -> o.end = p.end)
                .documentation("The last day of the event, as MM-DD; the event runs through the whole of it. An "
                        + "End before its Start runs into the next year. Not used when a Rule is authored.").add()
                .appendInherited(new KeyedCodec<>("Rule", WindowRules.CODEC, false),
                        (o, v) -> o.rule = v, o -> o.rule, (o, p) -> o.rule = p.rule)
                .documentation("How each year's runs are worked out: Fixed (the same month-days every year), "
                        + "Easter (days around Easter Sunday), Weekday (days around the Nth weekday of a month), "
                        + "Monthly (a run each month) or Weekly (a run each week), the last two with a time of day, "
                        + "a length in hours, every Nth week or month and the months they run in. Fixed is how a "
                        + "server owner pins an event whose days move. A Rule wins over Start and End.").add()
                .appendInherited(new KeyedCodec<>("Years", YEARS_CODEC, false),
                        (o, v) -> o.years = v, o -> o.years, (o, p) -> o.years = p.years)
                .documentation("Days for particular years, keyed by the four-digit year: {\"2031\": {\"Start\": "
                        + "\"04-01\", \"End\": \"04-20\"}}, several runs that year with {\"Runs\": [...]}, or none "
                        + "with {\"Runs\": []}. A year given days here runs on them alone, whatever the Rule or Start "
                        + "and End say. {\"Skip\": [3, 7]} leaves those run numbers out of that year; written alone, "
                        + "it keeps the rest of the year's runs. A Window of Years alone has no run in a year it does "
                        + "not list.").add()
                .build();

        public Window() {
        }

        /** Nothing authored at all. */
        boolean isEmpty() {
            return start == null && end == null && rule == null && (years == null || years.isEmpty());
        }

        @Nonnull
        Map<String, WindowRules.YearDays> yearsOrEmpty() {
            return years == null ? Map.of() : years;
        }
    }

    /**
     * The Moon group: the phases of one world's moon the event is live in. {@code MoonPhaseRange} is the engine's
     * own key, read by its rule ({@link MoonRange#within}); {@code World} names the world, blank for the default.
     */
    public static final class Moon {

        @Nullable private int[] moonPhaseRange;
        @Nullable private String world;

        public static final BuilderCodec<Moon> CODEC = BuilderCodec.builder(Moon.class, Moon::new)
                .appendInherited(new KeyedCodec<>("MoonPhaseRange", Codec.INT_ARRAY, false),
                        (o, v) -> o.moonPhaseRange = v, o -> o.moonPhaseRange,
                        (o, p) -> o.moonPhaseRange = p.moonPhaseRange)
                .documentation("The moon phases the event is live in, as [first, last], read the way a world "
                        + "spawn's MoonPhaseRange is: phase 0 is the full moon, and the moon wanes through each phase "
                        + "after it (the game's default has five, 0 to 4, each changing at midday). Both phases are "
                        + "in it, and a first past its last wraps round, so [4, 1] is phases 4, 0 and 1. [0, 1] is "
                        + "the full-moon night and the night after it. Required: two phases of 0 or more.").add()
                .appendInherited(new KeyedCodec<>("World", Codec.STRING, false),
                        (o, v) -> o.world = v, o -> o.world, (o, p) -> o.world = p.world)
                .metadata(EditorSchema.defaultValue(""))
                .documentation("The world whose moon it follows, by name. Unauthored or blank means the server's "
                        + "default world. A world whose time is paused keeps its moon, so the event stays as it is "
                        + "there.").add()
                .build();

        public Moon() {
        }
    }

    /**
     * The Live group: the other events this one is live by ({@link LiveRule}). Each list names event ids, and the
     * lists merge whole under {@code Parent} and in an owner entry, leaf by leaf.
     */
    public static final class Live {

        @Nullable private String[] anyOf;
        @Nullable private String[] allOf;
        @Nullable private String[] noneOf;

        public static final BuilderCodec<Live> CODEC = BuilderCodec.builder(Live.class, Live::new)
                .appendInherited(new KeyedCodec<>("AnyOf", Codec.STRING_ARRAY, false),
                        (o, v) -> o.anyOf = v, o -> o.anyOf, (o, p) -> o.anyOf = p.anyOf)
                .documentation("Calendar events by id, any of which being live makes this one live. Unauthored or "
                        + "empty asks nothing of it.").add()
                .appendInherited(new KeyedCodec<>("AllOf", Codec.STRING_ARRAY, false),
                        (o, v) -> o.allOf = v, o -> o.allOf, (o, p) -> o.allOf = p.allOf)
                .documentation("Calendar events by id that must all be live for this one to be.").add()
                .appendInherited(new KeyedCodec<>("NoneOf", Codec.STRING_ARRAY, false),
                        (o, v) -> o.noneOf = v, o -> o.noneOf, (o, p) -> o.noneOf = p.noneOf)
                .documentation("Calendar events by id, any of which being live keeps this one from being live. An "
                        + "event this server lacks or has switched off counts as not live.").add()
                .build();

        public Live() {
        }
    }

    /** The Presentation group: what the event is called and what stands for it. */
    public static final class Presentation {

        @Nullable private String titleKey;
        @Nullable private String flavorKey;
        @Nullable private String icon;

        public static final BuilderCodec<Presentation> CODEC = BuilderCodec.builder(Presentation.class, Presentation::new)
                .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                        (o, v) -> o.titleKey = v, o -> o.titleKey, (o, p) -> o.titleKey = p.titleKey)
                .documentation("Localization key for the event's name, in your own lang file.").add()
                .appendInherited(new KeyedCodec<>("FlavorKey", Codec.STRING, false),
                        (o, v) -> o.flavorKey = v, o -> o.flavorKey, (o, p) -> o.flavorKey = p.flavorKey)
                .documentation("Localization key for a line about the event.").add()
                .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                        (o, v) -> o.icon = v, o -> o.icon, (o, p) -> o.icon = p.icon)
                .metadata(EditorSchema.assetRef(Item.class))
                .documentation("The item whose picture stands for the event wherever events are listed.").add()
                .build();

        public Presentation() {
        }

        @Nullable
        public String titleKey() {
            return blankToNull(titleKey);
        }

        @Nullable
        public String flavorKey() {
            return blankToNull(flavorKey);
        }

        @Nullable
        public String icon() {
            return blankToNull(icon);
        }
    }

    /**
     * The Herald group: the banner at a run's start and at its end, and which runs show them: every run; the start
     * banner on the year's first run and the end banner when its last run ends, both by their dates; or none.
     */
    public static final class Herald {

        @Nullable private HeraldLine start;
        @Nullable private HeraldLine end;
        @Nullable private Boolean enabled;
        @Nullable private Boolean firstRunOfYear;
        @Nullable private Boolean oncePerDay;

        public static final BuilderCodec<Herald> CODEC = BuilderCodec.builder(Herald.class, Herald::new)
                .appendInherited(new KeyedCodec<>("Start", HeraldLine.CODEC, false),
                        (o, v) -> o.start = v, o -> o.start, (o, p) -> o.start = p.start)
                .documentation("Shown to each player once per run, the first time they are on the server while it "
                        + "runs.").add()
                .appendInherited(new KeyedCodec<>("End", HeraldLine.CODEC, false),
                        (o, v) -> o.end = v, o -> o.end, (o, p) -> o.end = p.end)
                .documentation("Shown to everyone online when a run ends by its dates or by a command; never when "
                        + "the owner switches the event off, nor when the event's next run begins at once (its start "
                        + "banner speaks instead).").add()
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether the event shows its banners at all; unauthored means true. False keeps every "
                        + "run quiet, which is how a server owner quiets a pack's event.").add()
                .appendInherited(new KeyedCodec<>("FirstRunOfYear", Codec.BOOLEAN, false),
                        (o, v) -> o.firstRunOfYear = v, o -> o.firstRunOfYear,
                        (o, p) -> o.firstRunOfYear = p.firstRunOfYear)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Show the start banner only on the year's first run and the end banner only when its "
                        + "last run ends, both by their dates; unauthored means false, so every run shows them. For an "
                        + "event that comes round each week or month.").add()
                .appendInherited(new KeyedCodec<>("OncePerDay", Codec.BOOLEAN, false),
                        (o, v) -> o.oncePerDay = v, o -> o.oncePerDay, (o, p) -> o.oncePerDay = p.oncePerDay)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("For an event with a Moon or a Live: show each player the start banner at most once a "
                        + "day, and the end banner at most once a day, counted in the event's Clock. Unauthored means "
                        + "false, so the start banner shows at every rise (to everyone online, and to a player who "
                        + "joins while it is live) and the end banner every time it sets. Not used by an event on "
                        + "dates.").add()
                .build();

        public Herald() {
        }
    }

    /** One banner: a title, an optional line under it, and whether it is the large style. */
    public static final class HeraldLine {

        @Nullable private String titleKey;
        @Nullable private String subtitleKey;
        @Nullable private Boolean major;

        public static final BuilderCodec<HeraldLine> CODEC = BuilderCodec.builder(HeraldLine.class, HeraldLine::new)
                .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                        (o, v) -> o.titleKey = v, o -> o.titleKey, (o, p) -> o.titleKey = p.titleKey)
                .documentation("Localization key for the banner's title, in your own lang file. No title, no "
                        + "banner.").add()
                .appendInherited(new KeyedCodec<>("SubtitleKey", Codec.STRING, false),
                        (o, v) -> o.subtitleKey = v, o -> o.subtitleKey, (o, p) -> o.subtitleKey = p.subtitleKey)
                .documentation("Localization key for the smaller line under the title.").add()
                .appendInherited(new KeyedCodec<>("Major", Codec.BOOLEAN, false),
                        (o, v) -> o.major = v, o -> o.major, (o, p) -> o.major = p.major)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Whether the banner uses the large style.").add()
                .build();

        public HeraldLine() {
        }

        @Nullable
        public String titleKey() {
            return blankToNull(titleKey);
        }

        @Nullable
        public String subtitleKey() {
            return blankToNull(subtitleKey);
        }

        public boolean major() {
            return major != null && major;
        }
    }
}
