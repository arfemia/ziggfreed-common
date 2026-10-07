package com.ziggfreed.common.calendar.asset;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
import com.ziggfreed.common.calendar.YearRule;
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
 * <p><b>Days that move</b>: a {@code Rule} works each year's days out, around Easter Sunday
 * ({@code {"Type": "Easter", "Before": 10, "After": 7}}) or the Nth weekday of a month
 * ({@code {"Type": "Weekday", "Month": 11, "Weekday": "Thursday", "Nth": 4}}), and {@code Years} sets the
 * days of particular years ({@code {"2031": {"Start": "04-01", "End": "04-20"}}}). Years win over a Rule,
 * a Rule over Start and End, and every run must start in its own year.
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
    /** A Start or End that is not a real MM-DD day, or a Rule missing a leaf it needs; the event never runs. */
    public static final String PROBLEM_WINDOW_UNREADABLE = "WINDOW_UNREADABLE";
    /** The file states no FirstYear. */
    public static final String PROBLEM_FIRST_YEAR_MISSING = "FIRST_YEAR_MISSING";
    /** A FirstYear before 1970 or after 9999; the event never runs. */
    public static final String PROBLEM_FIRST_YEAR_OUT_OF_RANGE = "FIRST_YEAR_OUT_OF_RANGE";
    /** A Clock java.time does not know; the event runs on UTC. */
    public static final String PROBLEM_CLOCK_UNKNOWN = "CLOCK_UNKNOWN";
    /** A Window Rule whose runs could start in the year before or last more than 366 days; the event never runs. */
    public static final String PROBLEM_WINDOW_RUN_INVALID = "WINDOW_RUN_INVALID";
    /** A Years entry that is not a four-digit year from FirstYear on, or whose days are not MM-DD; that entry is not used. */
    public static final String PROBLEM_YEARS_ENTRY_IGNORED = "YEARS_ENTRY_IGNORED";
    /** Start and End beside a Rule, which wins over them: a note, never a problem. */
    public static final String NOTE_START_END_IGNORED = "START_END_IGNORED";

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
    @Nullable private Integer firstYear;
    @Nullable private String clock;
    @Nullable private Presentation presentation;
    @Nullable private Herald herald;

    /** The Window read once per decoded file. */
    @Nullable private volatile ParsedWindow parsedWindow;

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
                    + "included, the same every year; a Rule (Easter, Weekday or Fixed) works each year's days out "
                    + "instead and wins over them; Years sets the days of particular years and wins over both. An "
                    + "End before its Start crosses the new year, and that run belongs to the year it starts in. A "
                    + "Window naming only some leaves under Parent or in an owner entry keeps the rest.")
            .add()
            .appendInherited(new KeyedCodec<>("FirstYear", Codec.INTEGER, false),
                    (a, v) -> a.firstYear = v, a -> a.firstYear, (a, p) -> a.firstYear = p.firstYear)
            .documentation("The first year the event runs, from " + MIN_FIRST_YEAR + " to " + MAX_FIRST_YEAR
                    + ". Runs before it never happen, and a list of past runs starts here. Required: an event "
                    + "without one, or with one outside those years, never runs.")
            .add()
            .appendInherited(new KeyedCodec<>("Clock", Codec.STRING, false),
                    (a, v) -> a.clock = v, a -> a.clock, (a, p) -> a.clock = p.clock)
            .metadata(EditorSchema.defaultValue("UTC"))
            .documentation("The time zone the Window's days are counted in: a zone id (UTC, Europe/Paris, "
                    + "America/New_York) or an offset (+02:00). Unauthored means UTC; a zone this server does not "
                    + "know also counts in UTC, with a warning.")
            .add()
            .appendInherited(new KeyedCodec<>("Presentation", Presentation.CODEC, false),
                    (a, v) -> a.presentation = v, a -> a.presentation, (a, p) -> a.presentation = p.presentation)
            .documentation("What the event is called and what stands for it wherever events are listed.")
            .add()
            .appendInherited(new KeyedCodec<>("Herald", Herald.CODEC, false),
                    (a, v) -> a.herald = v, a -> a.herald, (a, p) -> a.herald = p.herald)
            .documentation("The banner a player sees when a run begins for them and when it ends. Unauthored means "
                    + "the event comes and goes without one.")
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
        return parsed().notes();
    }

    @Nonnull
    private ParsedWindow parsed() {
        ParsedWindow cached = parsedWindow;
        if (cached == null) {
            cached = readWindow(window, firstYear);
            parsedWindow = cached;
        }
        return cached;
    }

    /** The Window read once: the days it gives, what stops or trims it, and what it authors that is unused. */
    private record ParsedWindow(@Nullable AnnualWindow window, @Nonnull List<String> problems,
                                @Nonnull List<String> notes) {
    }

    /**
     * Years win over a Rule, a Rule over Start and End. A Rule that cannot be read or could not keep each run
     * in its own year stops the event; a bad Years entry costs that entry alone.
     */
    @Nonnull
    private static ParsedWindow readWindow(@Nullable Window authored, @Nullable Integer firstYear) {
        if (authored == null || authored.isEmpty()) {
            return new ParsedWindow(null, List.of(PROBLEM_WINDOW_MISSING), List.of());
        }
        List<String> notes = new ArrayList<>();
        YearRule every = null;
        if (authored.rule != null) {
            if (authored.start != null || authored.end != null) {
                notes.add(NOTE_START_END_IGNORED);
            }
            every = authored.rule.toYearRule();
            if (every == null) {
                return new ParsedWindow(null, List.of(PROBLEM_WINDOW_UNREADABLE), List.copyOf(notes));
            }
            if (!every.valid()) {
                return new ParsedWindow(null, List.of(PROBLEM_WINDOW_RUN_INVALID), List.copyOf(notes));
            }
        } else if (authored.start != null || authored.end != null) {
            every = AnnualWindow.fixed(authored.start, authored.end);
            if (every == null) {
                return new ParsedWindow(null, List.of(PROBLEM_WINDOW_UNREADABLE), List.of());
            }
        }
        Map<Integer, YearRule.Fixed> years = new TreeMap<>();
        boolean ignored = false;
        for (Map.Entry<String, WindowRules.Fixed> entry : authored.yearsOrEmpty().entrySet()) {
            Integer year = yearKey(entry.getKey());
            YearRule.Fixed days = entry.getValue() == null ? null : entry.getValue().toFixed();
            if (year == null || days == null || (firstYear != null && year < firstYear)) {
                ignored = true;
            } else {
                years.put(year, days);
            }
        }
        List<String> problems = new ArrayList<>();
        AnnualWindow parsed = AnnualWindow.of(every, years);
        if (parsed == null) {
            problems.add(PROBLEM_WINDOW_UNREADABLE);
        }
        if (ignored) {
            problems.add(PROBLEM_YEARS_ENTRY_IGNORED);
        }
        return new ParsedWindow(parsed, List.copyOf(problems), List.copyOf(notes));
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

    /** Can the event run at all: an id of its own, a readable Window and a FirstYear from 1970 to 9999? */
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
        out.addAll(parsed().problems());
        if (firstYear == null) {
            out.add(PROBLEM_FIRST_YEAR_MISSING);
        } else if (!isFirstYearInRange(firstYear)) {
            out.add(PROBLEM_FIRST_YEAR_OUT_OF_RANGE);
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
     * particular years and wins over both. Years merge by year under {@code Parent} and in an owner entry.
     */
    public static final class Window {

        private static final InheritMapCodec<WindowRules.Fixed> YEARS_CODEC =
                new InheritMapCodec<>(WindowRules.Fixed.CODEC);

        @Nullable private String start;
        @Nullable private String end;
        @Nullable private WindowRules.Rule rule;
        @Nullable private Map<String, WindowRules.Fixed> years;

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
                .documentation("How each year's days are worked out when they move: Easter (days around Easter "
                        + "Sunday) or Weekday (days around the Nth weekday of a month). Fixed gives the same "
                        + "month-days every year, which is how a server owner pins an event whose days move. A "
                        + "Rule wins over Start and End.").add()
                .appendInherited(new KeyedCodec<>("Years", YEARS_CODEC, false),
                        (o, v) -> o.years = v, o -> o.years, (o, p) -> o.years = p.years)
                .documentation("Days for particular years, keyed by the four-digit year: {\"2031\": {\"Start\": "
                        + "\"04-01\", \"End\": \"04-20\"}}. A year listed here runs on these days whatever the Rule "
                        + "or Start and End say. A Window of Years alone has no run in a year it does not list.").add()
                .build();

        public Window() {
        }

        /** Nothing authored at all. */
        boolean isEmpty() {
            return start == null && end == null && rule == null && (years == null || years.isEmpty());
        }

        @Nonnull
        Map<String, WindowRules.Fixed> yearsOrEmpty() {
            return years == null ? Map.of() : years;
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

    /** The Herald group: the banner at a run's start and at its end. */
    public static final class Herald {

        @Nullable private HeraldLine start;
        @Nullable private HeraldLine end;

        public static final BuilderCodec<Herald> CODEC = BuilderCodec.builder(Herald.class, Herald::new)
                .appendInherited(new KeyedCodec<>("Start", HeraldLine.CODEC, false),
                        (o, v) -> o.start = v, o -> o.start, (o, p) -> o.start = p.start)
                .documentation("Shown to each player once per run, the first time they are on the server while it "
                        + "runs.").add()
                .appendInherited(new KeyedCodec<>("End", HeraldLine.CODEC, false),
                        (o, v) -> o.end = v, o -> o.end, (o, p) -> o.end = p.end)
                .documentation("Shown to everyone online when a run ends by its dates or by a command; never when "
                        + "the owner switches the event off.").add()
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
