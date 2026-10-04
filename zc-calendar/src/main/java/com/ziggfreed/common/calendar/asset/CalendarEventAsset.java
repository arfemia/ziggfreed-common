package com.ziggfreed.common.calendar.asset;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.calendar.AnnualWindow;

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
 * <p><b>{@code Enabled: false} makes the event ABSENT, not locked</b>: content gated on it vanishes,
 * as it does when the server owner switches every event off ({@code mods/ziggfreedcommon/calendar.json},
 * {@code "$Enabled": false}). An event with no readable {@code Window} or no {@code FirstYear} never
 * runs, and the server log says why.
 *
 * <p><b>Three ids are not an event's to take</b>, because an event's switches are features in the same
 * namespace as two others: {@code Calendar} (the owner's switch over every event), {@code Almanac} (the
 * Almanac's switch) and any id ending in {@code _Live} (an event's running switch is its id plus
 * {@code _Live}). A file under one of them never runs, and the server log says why.
 */
public final class CalendarEventAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, CalendarEventAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/CalendarEvents";

    /** The id is one a feature switch in the calendar's namespace already uses; the event never runs. */
    public static final String PROBLEM_ID_RESERVED = "ID_RESERVED";
    /** The file states no Window at all. */
    public static final String PROBLEM_WINDOW_MISSING = "WINDOW_MISSING";
    /** A Start or End that is not a real MM-DD day. */
    public static final String PROBLEM_WINDOW_UNREADABLE = "WINDOW_UNREADABLE";
    /** The file states no FirstYear. */
    public static final String PROBLEM_FIRST_YEAR_MISSING = "FIRST_YEAR_MISSING";
    /** A Clock java.time does not know; the event runs on UTC. */
    public static final String PROBLEM_CLOCK_UNKNOWN = "CLOCK_UNKNOWN";

    /**
     * The ids no event may take, lower-cased: the owner's switch over every event ({@code Calendar}) and
     * the Almanac's switch ({@code Almanac}), features in the same {@code ziggfreedcommon} namespace an
     * event's own switches are declared in.
     */
    private static final Set<String> RESERVED_IDS = Set.of("calendar", "almanac");

    /** An event's running switch is its id plus {@code _Live}, so no event id may end in it. Lower-cased. */
    private static final String RESERVED_SUFFIX = "_live";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private Window window;
    @Nullable private Integer firstYear;
    @Nullable private String clock;
    @Nullable private Presentation presentation;
    @Nullable private Herald herald;

    /** The parsed Window, computed once per decoded file. */
    @Nullable private volatile AnnualWindow parsedWindow;

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
            .documentation("The days the event runs each year, as MM-DD month-days with both days included. An End "
                    + "before its Start crosses the new year, and that run belongs to the year it starts in. A Window "
                    + "naming only one day under Parent or in an owner entry keeps the other.")
            .add()
            .appendInherited(new KeyedCodec<>("FirstYear", Codec.INTEGER, false),
                    (a, v) -> a.firstYear = v, a -> a.firstYear, (a, p) -> a.firstYear = p.firstYear)
            .documentation("The first year the event runs. Runs before it never happen, and a list of past runs "
                    + "starts here. Required: an event without one never runs.")
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

    /** The parsed Window, or null when the file states none or states a day that is not real. */
    @Nullable
    public AnnualWindow annualWindow() {
        AnnualWindow cached = parsedWindow;
        if (cached == null && window != null) {
            cached = AnnualWindow.parse(window.start, window.end);
            parsedWindow = cached;
        }
        return cached;
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

    /** Can the event run at all: an id of its own, a readable Window and a FirstYear? */
    public boolean canRun() {
        return !isReservedId(id) && annualWindow() != null && firstYear != null;
    }

    /**
     * Is {@code id} one no event may take? {@code Calendar} and {@code Almanac} name other switches in the
     * feature namespace an event's switches share, and an id ending in {@code _Live} would collide with
     * another event's running switch. Matched without regard to case.
     */
    public static boolean isReservedId(@Nullable String id) {
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
        if (isReservedId(id)) {
            out.add(PROBLEM_ID_RESERVED);
        }
        if (window == null || (window.start == null && window.end == null)) {
            out.add(PROBLEM_WINDOW_MISSING);
        } else if (annualWindow() == null) {
            out.add(PROBLEM_WINDOW_UNREADABLE);
        }
        if (firstYear == null) {
            out.add(PROBLEM_FIRST_YEAR_MISSING);
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

    /** The Window group: the first and the last day, as {@code MM-DD}. */
    public static final class Window {

        @Nullable private String start;
        @Nullable private String end;

        public static final BuilderCodec<Window> CODEC = BuilderCodec.builder(Window.class, Window::new)
                .appendInherited(new KeyedCodec<>("Start", Codec.STRING, false),
                        (o, v) -> o.start = v, o -> o.start, (o, p) -> o.start = p.start)
                .documentation("The first day of the event, as MM-DD (10-01 is October 1st).").add()
                .appendInherited(new KeyedCodec<>("End", Codec.STRING, false),
                        (o, v) -> o.end = v, o -> o.end, (o, p) -> o.end = p.end)
                .documentation("The last day of the event, as MM-DD; the event runs through the whole of it.").add()
                .build();

        public Window() {
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
                .metadata(new UIEditor(new UIEditor.Dropdown("hytale:item")))
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
