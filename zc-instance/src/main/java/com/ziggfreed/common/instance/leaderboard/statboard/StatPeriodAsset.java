package com.ziggfreed.common.instance.leaderboard.statboard;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * One period the stat board ranks over, as a file: {@code Server/ZiggfreedCommon/StatPeriods/<Id>.json}.
 * The filename is the id, and a week's or a month's buckets are named after it.
 *
 * <pre>{@code
 * {"Kind":"AllTime","NameKey":"ziggfreedcommon.statboard.period.all_time","Order":0}
 * {"Kind":"Weekly","NameKey":"ziggfreedcommon.statboard.period.weekly","StartsOn":"Monday","Zone":"UTC","Keep":2,"Order":10}
 * {"Kind":"Monthly","NameKey":"ziggfreedcommon.statboard.period.monthly","Zone":"UTC","Keep":2,"Order":20}
 * {"Kind":"SeasonRun","Event":"*","NameKey":"ziggfreedcommon.statboard.period.season","Keep":2,"Order":30}
 * {"Kind":"Range","NameKey":"myserver.stats.period.launch","From":"2026-10-12T00:00","To":"2026-10-19T00:00","Order":40}
 * }</pre>
 *
 * <p>The library ships All_Time, Weekly, Monthly and Season. A server owner retunes one, or adds a range, in
 * {@code mods/ziggfreedcommon/stats.json}: each top-level entry there is a period id and the leaves it
 * should read differently ({@link StatPeriodOwnerLayers}). A file that cannot run (an unknown kind, a zone
 * or a date that does not read) shows no tab and counts nothing, and says so once in the log.
 */
public final class StatPeriodAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, StatPeriodAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/StatPeriods";

    /** The day a week starts on when a file names none. */
    public static final DayOfWeek DEFAULT_STARTS_ON = DayOfWeek.MONDAY;

    /** How many windows of a week or a month, or runs of each event, the board keeps when a file says nothing. */
    public static final int DEFAULT_KEEP = 2;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String kind;
    @Nullable private String nameKey;
    @Nullable private Integer order;
    @Nullable private String startsOn;
    @Nullable private String zone;
    @Nullable private Integer keep;
    @Nullable private String from;
    @Nullable private String to;
    @Nullable private String event;

    public static final AssetBuilderCodec<String, StatPeriodAsset> CODEC = AssetBuilderCodec.builder(
                    StatPeriodAsset.class,
                    StatPeriodAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                    (a, v) -> a.kind = v, a -> a.kind, (a, parent) -> a.kind = parent.kind)
            .metadata(EditorSchema.oneOfDocumented(
                    PeriodKind.ALL_TIME.authored(), "One window that never ends",
                    PeriodKind.WEEKLY.authored(), "A calendar week, from StartsOn, in Zone",
                    PeriodKind.MONTHLY.authored(), "A calendar month, in Zone",
                    PeriodKind.SEASON_RUN.authored(), "One window per run of a calendar event (Event), readable "
                            + "after the run ends",
                    PeriodKind.RANGE.authored(), "One window from From up to To, readable after it ends"))
            .documentation("How the period's windows are cut. A new window starts empty; the old ones are "
                    + "kept as Keep says.").add()
            .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                    (a, v) -> a.nameKey = v, a -> a.nameKey, (a, parent) -> a.nameKey = parent.nameKey)
            .documentation("The full translation key the period's tab is named by. Omit it and the tab shows "
                    + "the file's id. A SeasonRun names one tab per run: its value takes {0}, the event's name, "
                    + "and {1}, the year the run belongs to, and omitted the tab reads the event's name and the "
                    + "year.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, parent) -> a.order = parent.order)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Where the period's tab sits among the others, lowest first.").add()
            .appendInherited(new KeyedCodec<>("StartsOn", Codec.STRING, false),
                    (a, v) -> a.startsOn = v, a -> a.startsOn, (a, parent) -> a.startsOn = parent.startsOn)
            .metadata(EditorSchema.oneOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
                    "Sunday"))
            .metadata(EditorSchema.defaultValue("Monday"))
            .documentation("The day a Weekly period's week starts on. Unauthored means Monday. Other kinds "
                    + "ignore it.").add()
            .appendInherited(new KeyedCodec<>("Zone", Codec.STRING, false),
                    (a, v) -> a.zone = v, a -> a.zone, (a, parent) -> a.zone = parent.zone)
            .metadata(EditorSchema.defaultValue("UTC"))
            .documentation("The clock a week, a month or a range is read on, as a time-zone id such as UTC "
                    + "or America/New_York. Unauthored means UTC.").add()
            .appendInherited(new KeyedCodec<>("Keep", Codec.INTEGER, false),
                    (a, v) -> a.keep = v, a -> a.keep, (a, parent) -> a.keep = parent.keep)
            .metadata(EditorSchema.defaultValue((long) DEFAULT_KEEP))
            .documentation("How many windows of a Weekly or Monthly period the board keeps, the running one "
                    + "included, and how many runs of each event a SeasonRun keeps; older ones are dropped. "
                    + "Unauthored means 2, and less than 1 means 1.").add()
            .appendInherited(new KeyedCodec<>("From", Codec.STRING, false),
                    (a, v) -> a.from = v, a -> a.from, (a, parent) -> a.from = parent.from)
            .documentation("A Range's first moment, as 2026-10-12T00:00 on Zone's clock. Unauthored means it "
                    + "has always been running.").add()
            .appendInherited(new KeyedCodec<>("To", Codec.STRING, false),
                    (a, v) -> a.to = v, a -> a.to, (a, parent) -> a.to = parent.to)
            .documentation("The moment a Range ends, left out of it, as 2026-10-19T00:00 on Zone's clock. "
                    + "Unauthored means it never ends.").add()
            .appendInherited(new KeyedCodec<>("Event", Codec.STRING, false),
                    (a, v) -> a.event = v, a -> a.event, (a, parent) -> a.event = parent.event)
            .metadata(EditorSchema.defaultValue(StatPeriod.EVERY_EVENT))
            .documentation("The calendar event a SeasonRun period follows, by its id. Unauthored, or a lone "
                    + "asterisk, follows every event that is live. Each run gets its own tab while it is live "
                    + "and keeps it after it ends. Other kinds ignore it.").add()
            .build();

    public StatPeriodAsset() {
    }

    /** The period id, lower-cased from the filename. */
    @Override
    public String getId() {
        return id;
    }

    /**
     * The period as the board runs it, or null when it cannot run; {@code problem} then names why. A
     * resolved period never throws later: every word and date here is read once.
     */
    @Nullable
    public StatPeriod toPeriod(@Nonnull String periodId) {
        return problem() != null ? null : new StatPeriod(periodId.toLowerCase(Locale.ROOT),
                PeriodKind.fromAuthored(kind), blankToNull(nameKey), order == null ? 0 : order, zoneId(),
                dayOfWeek(), keep == null ? DEFAULT_KEEP : Math.max(1, keep), dateTime(from), dateTime(to),
                followedEvent());
    }

    /** Why this file cannot run, as a line for the log; null when it can. */
    @Nullable
    public String problem() {
        if (PeriodKind.fromAuthored(kind) == null) {
            return "its Kind '" + kind + "' is none of AllTime, Weekly, Monthly, SeasonRun or Range";
        }
        String followed = followedEvent();
        if (followed != null && !PeriodWindows.usableEvent(followed)) {
            return "its Event '" + event + "' cannot name a calendar event";
        }
        if (zone != null && !zone.isBlank() && parseZone(zone) == null) {
            return "its Zone '" + zone + "' is not a time zone";
        }
        if (startsOn != null && !startsOn.isBlank() && parseDay(startsOn) == null) {
            return "its StartsOn '" + startsOn + "' is not a day of the week";
        }
        if (from != null && !from.isBlank() && parseDateTime(from) == null) {
            return "its From '" + from + "' is not a date and time like 2026-10-12T00:00";
        }
        if (to != null && !to.isBlank() && parseDateTime(to) == null) {
            return "its To '" + to + "' is not a date and time like 2026-10-19T00:00";
        }
        return null;
    }

    /** The event a season follows, lower-cased; null for every event (unauthored, blank or {@code *}). */
    @Nullable
    private String followedEvent() {
        String trimmed = event == null ? "" : event.trim();
        return trimmed.isEmpty() || trimmed.equals(StatPeriod.EVERY_EVENT) ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private ZoneId zoneId() {
        ZoneId parsed = zone == null || zone.isBlank() ? null : parseZone(zone);
        return parsed == null ? ZoneOffset.UTC : parsed;
    }

    @Nonnull
    private DayOfWeek dayOfWeek() {
        DayOfWeek parsed = startsOn == null || startsOn.isBlank() ? null : parseDay(startsOn);
        return parsed == null ? DEFAULT_STARTS_ON : parsed;
    }

    @Nullable
    private static LocalDateTime dateTime(@Nullable String authored) {
        return authored == null || authored.isBlank() ? null : parseDateTime(authored);
    }

    @Nullable
    private static ZoneId parseZone(@Nonnull String authored) {
        try {
            return ZoneId.of(authored.trim());
        } catch (DateTimeException bad) {
            return null;
        }
    }

    @Nullable
    private static DayOfWeek parseDay(@Nonnull String authored) {
        try {
            return DayOfWeek.valueOf(authored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    @Nullable
    private static LocalDateTime parseDateTime(@Nonnull String authored) {
        try {
            return LocalDateTime.parse(authored.trim());
        } catch (DateTimeException bad) {
            return null;
        }
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
