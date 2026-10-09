package com.ziggfreed.common.almanac.view;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.almanac.AlmanacCalendar;
import com.ziggfreed.common.almanac.AlmanacCalendar.Dates;
import com.ziggfreed.common.almanac.AlmanacCollection;
import com.ziggfreed.common.almanac.AlmanacKeys;
import com.ziggfreed.common.almanac.ServerTallies;
import com.ziggfreed.common.almanac.asset.AlmanacAchievementsAsset;
import com.ziggfreed.common.almanac.asset.AlmanacBannerAsset;
import com.ziggfreed.common.almanac.asset.AlmanacCollectionAsset;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacHeroAsset;
import com.ziggfreed.common.almanac.asset.AlmanacLinkAsset;
import com.ziggfreed.common.almanac.asset.AlmanacSectionAsset;
import com.ziggfreed.common.almanac.asset.AlmanacStatAsset;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.inventory.ItemIds;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.Recurrence;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.KeepsakeState;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * What the Almanac shows, as plain data: which seasons it lists, what one season's page says for one
 * player, and the cross-season banner. Every decision the page makes lives here, where a test can
 * reach it; the page only paints.
 *
 * <p><b>Achievements are found by where content files them.</b> A season's own are filed under
 * {@value #SEASONS_CATEGORY} with the event id as subcategory; a cross-season one under
 * {@value #SEASONS_CATEGORY} with no subcategory (the banner shows the ladder's next rung); a keepsake is
 * the catalogue's yearly copy of the page's {@code Keepsake}, found by the occurrence it was minted for
 * ({@link Achievement#occurrence()}), or by its {@code <Keepsake>_<yyyy>} id when it carries none.
 *
 * <p><b>Dates are counted in the event's own clock</b>, the last day included: on a run's last day the
 * page says "Last day", and a run from October 1 to November 3 has 28 days left on October 7.
 */
public final class AlmanacView {

    /** The category every season's achievements file under. */
    public static final String SEASONS_CATEGORY = "seasons";

    /** The hero plate's size: what a composed hero's items are kept inside. */
    public static final int HERO_WIDTH = 962;
    public static final int HERO_HEIGHT = 240;

    /** How many items a composed hero draws. */
    public static final int HERO_MAX_ITEMS = AlmanacEntryAsset.HERO_MAX_ITEMS;

    /** A hero item's side: unauthored, and the range it is kept to. */
    public static final int HERO_ITEM_SIZE = 64;
    public static final int HERO_ITEM_MIN = 24;
    public static final int HERO_ITEM_MAX = 128;

    /** A hero glow's box: unauthored side, the side's range, and how far the box may hang past the plate. */
    public static final int GLOW_SIZE = 240;
    public static final int GLOW_MIN = 32;
    public static final int GLOW_MAX = 480;
    public static final int GLOW_X_MIN = -480;
    public static final int GLOW_X_MAX = 1442;
    public static final int GLOW_Y_MIN = -480;
    public static final int GLOW_Y_MAX = 720;

    /**
     * An inline banner's plate: the body's content width ({@code AlmanacLayout.CONTENT_WIDTH}, which the
     * page's document test holds equal), its unauthored height, and the range its height is kept to.
     */
    public static final int BANNER_WIDTH = 906;
    public static final int BANNER_HEIGHT = 120;
    public static final int BANNER_MIN = 64;
    public static final int BANNER_MAX = 240;

    /** How many items a collection's grid draws. */
    public static final int COLLECTION_MAX_ITEMS = AlmanacEntryAsset.COLLECTION_MAX_ITEMS;

    /** How many {@code Sections} entries a page draws. */
    public static final int SECTIONS_MAX = AlmanacEntryAsset.SECTIONS_MAX;

    /**
     * The book's destination type, zc-objectives' {@code ObjectiveBookDestinations.ACHIEVEMENTS_TYPE}, named
     * here as a string because the Almanac has no edge to that module: it reaches the book through the shared
     * vocabulary alone.
     */
    public static final String ACHIEVEMENTS_TYPE = "Achievements";

    /** The two leaves the call to action writes on the book's destination: its category and subcategory. */
    private static final String BOOK_CATEGORY_KEY = "Category";
    private static final String BOOK_SUBCATEGORY_KEY = "Subcategory";

    /** A season returning within this many days is "soon": its chip and row take the near tone. */
    public static final int SOON_DAYS = 14;

    /** How dark a composed hero's derived background is: the accent at this share of its brightness. */
    private static final double DERIVED_BACKGROUND_SHARE = 0.35;

    /** One listed season. {@code liveYear} is the season on right now's opening year, 0 when none is on. */
    public record Season(@Nonnull String eventId, @Nullable String titleKey, @Nullable String flavorKey,
                         @Nullable String icon, boolean live, int liveYear) {
    }

    /** One tally line: what it counts, how it reads, and the number. */
    public record StatLine(@Nonnull String statId, @Nullable String textKey, @Nullable String icon, long count) {
    }

    /** One year's keepsake the player earned. */
    public record Keepsake(@Nonnull String achievementId, int year, @Nullable String icon) {
    }

    /** One feat of the season the player earned. */
    public record Feat(@Nonnull String achievementId, @Nullable String icon) {
    }

    /**
     * The cross-season achievement shown above the list (the ladder's next rung, see {@link #banner}), with
     * its count: the engine's tally, full once earned.
     */
    public record Banner(@Nonnull String achievementId, boolean earned, int childrenEarned, int childrenTotal) {
    }

    /**
     * Everything one season's page says for one player. {@code shownYear} is the live season's year,
     * else the last season the player has tallies for, else null (no season section).
     */
    public record Detail(@Nonnull Season season, @Nullable Integer shownYear, boolean attendedShownYear,
                         long seasonsAttended, @Nonnull List<StatLine> seasonLines,
                         @Nonnull List<StatLine> lifetimeLines, @Nonnull List<Keepsake> keepsakes,
                         int achievementsEarned, int achievementsListed, @Nonnull List<Feat> feats) {
    }

    /**
     * When a season runs, as its chip, its row and its dates line read it. While it is on: the days left
     * (the last day counted; null for a run forced on outside its dates) and whether today is the last
     * day. Between runs: the days until the next start, and whether that is within {@link #SOON_DAYS}.
     * The window's month-days come from the run going on, else the next, else the last; {@code
     * startsLater} is a season whose first run is still ahead. {@code windowYear} is the framing run's
     * year when the season's days move, null when they are the same every year. {@code recurring} is how a
     * season that comes round monthly or weekly recurs, with its next run, null for any other season.
     */
    public record Timing(boolean live, @Nullable Integer daysLeft, boolean lastDay, @Nullable Integer daysUntil,
                         boolean soon, @Nullable MonthDay windowStart, @Nullable MonthDay windowEnd,
                         @Nullable LocalDate nextStart, boolean startsLater, @Nullable Integer windowYear,
                         @Nullable Recurring recurring) {

        /** A season whose days are the same every year: no run's year to name. */
        public Timing(boolean live, @Nullable Integer daysLeft, boolean lastDay, @Nullable Integer daysUntil,
                boolean soon, @Nullable MonthDay windowStart, @Nullable MonthDay windowEnd,
                @Nullable LocalDate nextStart, boolean startsLater) {
            this(live, daysLeft, lastDay, daysUntil, soon, windowStart, windowEnd, nextStart, startsLater, null);
        }

        /** A season that does not come round monthly or weekly. */
        public Timing(boolean live, @Nullable Integer daysLeft, boolean lastDay, @Nullable Integer daysUntil,
                boolean soon, @Nullable MonthDay windowStart, @Nullable MonthDay windowEnd,
                @Nullable LocalDate nextStart, boolean startsLater, @Nullable Integer windowYear) {
            this(live, daysLeft, lastDay, daysUntil, soon, windowStart, windowEnd, nextStart, startsLater, windowYear,
                    null);
        }
    }

    /**
     * How a season that comes round monthly or weekly recurs, as the calendar's rule says it, and its next run's
     * first instant and the first instant after it, on the season's own clock (both null when none is due): what
     * its two dates lines say in place of one run's days, the rule and then the next run.
     */
    public record Recurring(@Nonnull Recurrence rule, @Nullable LocalDateTime nextStart,
                            @Nullable LocalDateTime nextEnd) {
    }

    /**
     * One year a player can read: whether it is the run on now, whether they took part, its keepsake, and how many
     * of that year's runs they attended (an event may come round several times a year).
     */
    public record YearChip(int year, boolean live, boolean tookPart, boolean keepsakeEarned, int runsAttended) {

        /** A year whose runs attended are not counted. */
        public YearChip(int year, boolean live, boolean tookPart, boolean keepsakeEarned) {
            this(year, live, tookPart, keepsakeEarned, 0);
        }
    }

    /** Which figures a page reads: one season's year, or every season ({@link #EVERY}, a null year). */
    public record Scope(@Nullable Integer year) {

        /** Every season together: lifetime figures. */
        public static final Scope EVERY = new Scope(null);

        /** Is this every season rather than one year? */
        public boolean every() {
            return year == null;
        }
    }

    /**
     * One tile: the scope's figure, the every-season count as its caption while a year is shown (null on
     * every season), and what everyone on the server counted in the same scope (null until someone has).
     */
    public record Tally(@Nonnull String statId, @Nullable String textKey, @Nullable String icon, long figure,
                        @Nullable Long allSeasons, @Nullable Long server) {
    }

    /** The record card: seasons taken part in and keepsakes earned, across every listed season. */
    public record Record(long seasonsTakenPart, long keepsakes) {
    }

    /** The season's achievements: how many of those listed are earned, and its earned feats. */
    public record SeasonAchievements(int earned, int total, @Nonnull List<Feat> feats) {
    }

    /** A line at the foot of a season's page that opens another screen. */
    public record SeasonLink(@Nonnull String textKey, @Nonnull Destination destination) {
    }

    /** One part of a season's page body, in the order it is drawn. */
    public sealed interface Section permits TalliesSection, KeepsakesSection, AchievementsSection, LinksSection,
            BannerSection, CollectionSection {
    }

    /** The year chips and the tallies. */
    public record TalliesSection() implements Section {
    }

    /** The keepsake shelf. */
    public record KeepsakesSection() implements Section {
    }

    /** The page's links. */
    public record LinksSection() implements Section {
    }

    /**
     * The season's achievements, with the button into the book: where it goes (null: no button) and its words
     * (null: the library's own).
     */
    public record AchievementsSection(@Nullable Destination destination, @Nullable String textKey) implements Section {
    }

    /** An inline banner: its shipped art, else its composition (never both), its height, its words and its button. */
    public record BannerSection(@Nullable String art, @Nullable HeroComposition composition, int height,
            @Nullable String titleKey, @Nullable String flavorKey, @Nullable SeasonLink button) implements Section {
    }

    /** One item of a collection: its picture, whether the player has had it once, whether it hides until then, its source line. */
    public record CollectionItem(@Nonnull String itemId, @Nonnull String iconPath, boolean owned, boolean hidden,
            @Nullable String sourceKey) {
    }

    /** The items a player can find this season, in order, with the grid's words and its button. */
    public record CollectionSection(@Nullable String titleKey, @Nullable String flavorKey,
            @Nonnull List<CollectionItem> items, @Nullable SeasonLink button) implements Section {

        /** How many of the items the player has had once. */
        public int owned() {
            int owned = 0;
            for (CollectionItem item : items) {
                if (item.owned()) {
                    owned++;
                }
            }
            return owned;
        }
    }

    /** The order a page that writes no Sections reads, before its button into the book is resolved. */
    public static final List<Section> TODAYS_ORDER = List.of(new TalliesSection(), new KeepsakesSection(),
            new AchievementsSection(null, null), new LinksSection());

    /** One month of the year at a glance (1 to 12) and the seasons running in it, in list order. */
    public record MonthMarks(int month, @Nonnull List<String> eventIds) {
    }

    /** One item picture on a composed hero: its own icon texture, its top-left corner, its side. */
    public record HeroItem(@Nonnull String itemId, @Nonnull String iconPath, int x, int y, int size) {
    }

    /** A composed hero's vertical sky: the colour at the plate's top edge (y 0) and at its bottom (y 240). */
    public record HeroGradient(@Nonnull String topHex, @Nonnull String bottomHex) {
    }

    /**
     * A composed hero's soft light: its colour and its square box by the top-left corner, kept to its
     * bounds but NOT to the plate (the box may hang past any edge; the page clips it to the plate).
     */
    public record HeroGlow(@Nonnull String colorHex, int x, int y, int size) {
    }

    /**
     * A hero composed from pictures the game has, drawn bottom up: {@code backgroundHex} (null under a
     * gradient, and when neither the composition nor the season names a colour), the {@code gradient},
     * the {@code backgroundTexture}, the {@code glow}, then the {@code items} in order (at least one).
     */
    public record HeroComposition(@Nullable String backgroundHex, @Nullable String backgroundTexture,
                                  @Nullable HeroGradient gradient, @Nullable HeroGlow glow,
                                  @Nonnull List<HeroItem> items) {
    }

    /**
     * Which top a season's page draws: the shipped {@code art} when it is shown and ships; else the
     * {@code composition}; else the season's own picture, {@code iconPath} (null for a season with no
     * picture the server has: the plate alone).
     */
    public record Hero(@Nullable String art, @Nullable HeroComposition composition, @Nullable String iconPath) {
    }

    /**
     * One keepsake tile: a year the season ran (or the player counted in, or earned a copy for), oldest
     * first; earned, still to earn (the run on now), or missed. {@code achievementId} is that year's copy
     * (null when the catalogue has none for the year); {@code icon} is the copy's item, else any copy's.
     */
    public record YearKeepsake(int year, @Nonnull KeepsakeState state, @Nullable String achievementId,
                               @Nullable String icon) {
    }

    /**
     * Everything a season's page says for one player: when it runs, the years to read and the scope read,
     * whether the player took part in that scope, the tiles, the keepsake shelf (null: the section is
     * absent, for a season with no keepsake, no subject loaded, or a first run still ahead), the
     * achievements (null: absent, nothing filed or no subject), the resolved hero, the season's authored
     * accent ({@code #rrggbb}, unclamped; null when none), the links, and the body's parts in the order
     * drawn ({@link #sections}).
     */
    public record SeasonPage(@Nonnull Season season, @Nonnull Timing timing, @Nonnull List<YearChip> years,
                             @Nonnull Scope scope, boolean tookPartInScope, @Nonnull List<Tally> tallies,
                             @Nullable List<YearKeepsake> keepsakes, @Nullable SeasonAchievements achievements,
                             @Nonnull Hero hero, @Nullable String accentHex, @Nonnull List<SeasonLink> links,
                             @Nonnull List<Section> sections) {

        /** A page in today's order ({@link #TODAYS_ORDER}), with no button into the book. */
        public SeasonPage(@Nonnull Season season, @Nonnull Timing timing, @Nonnull List<YearChip> years,
                @Nonnull Scope scope, boolean tookPartInScope, @Nonnull List<Tally> tallies,
                @Nullable List<YearKeepsake> keepsakes, @Nullable SeasonAchievements achievements,
                @Nonnull Hero hero, @Nullable String accentHex, @Nonnull List<SeasonLink> links) {
            this(season, timing, years, scope, tookPartInScope, tallies, keepsakes, achievements, hero, accentHex,
                    links, TODAYS_ORDER);
        }
    }

    private AlmanacView() {
    }

    /**
     * Every season to list: each page whose event the calendar answers for, the seasons on now first,
     * then by Order, then id, within each group.
     */
    @Nonnull
    public static List<Season> seasons(@Nonnull Map<String, AlmanacEntryAsset> pages,
            @Nonnull AlmanacCalendar calendar) {
        List<Map.Entry<String, AlmanacEntryAsset>> ordered = new ArrayList<>(pages.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, AlmanacEntryAsset> e) -> e.getValue().orderOrLast())
                .thenComparing(e -> AlmanacKeys.normalize(e.getKey())));
        List<Season> live = new ArrayList<>();
        List<Season> rest = new ArrayList<>();
        for (Map.Entry<String, AlmanacEntryAsset> entry : ordered) {
            String eventId = AlmanacKeys.normalize(entry.getKey());
            if (!AlmanacKeys.usableId(eventId)) {
                continue;
            }
            AlmanacCalendar.SeasonState state = calendar.state(eventId);
            if (state == null) {
                continue;
            }
            AlmanacEntryAsset page = entry.getValue();
            Season season = new Season(eventId, page.titleKey(), page.flavorKey(), page.getIcon(), state.live(),
                    state.live() ? state.year() : 0);
            (season.live() ? live : rest).add(season);
        }
        live.addAll(rest);
        return List.copyOf(live);
    }

    /**
     * Is any season on right now? True when at least one season {@link #seasons} lists reads live, false
     * when none does. An event the calendar does not answer for is absent, so it is never live. A pure
     * read, for a consumer's menu that shows a seasonal tab only while a season runs; whether the Almanac
     * itself is switched on is {@code AlmanacPages.available()}'s question, not this one.
     */
    public static boolean anySeasonLive(@Nonnull Map<String, AlmanacEntryAsset> pages,
            @Nonnull AlmanacCalendar calendar) {
        for (Season season : seasons(pages, calendar)) {
            if (season.live()) {
                return true;
            }
        }
        return false;
    }

    /** The season to open on: the one asked for, else the first one on now, else the first; null for none. */
    @Nullable
    public static Season pick(@Nonnull List<Season> seasons, @Nullable String requested) {
        if (seasons.isEmpty()) {
            return null;
        }
        if (requested != null && !requested.isBlank()) {
            String wanted = AlmanacKeys.normalize(requested);
            for (Season season : seasons) {
                if (season.eventId().equals(wanted)) {
                    return season;
                }
            }
        }
        for (Season season : seasons) {
            if (season.live()) {
                return season;
            }
        }
        return seasons.get(0);
    }

    /** One season's page for one player. A null engine or subject leaves the achievement half empty. */
    @Nonnull
    public static Detail detail(@Nonnull Season season, @Nullable AlmanacEntryAsset page,
            @Nonnull CounterMap tallies, @Nullable AchievementEngine engine, @Nullable Subject subject) {
        String eventId = season.eventId();
        SortedSet<Integer> years = AlmanacKeys.seasonYears(tallies, eventId);
        Integer shown = season.live() ? Integer.valueOf(season.liveYear()) : (years.isEmpty() ? null : years.last());
        List<StatLine> seasonLines = shown == null ? List.of() : lines(page, tallies, eventId, shown);
        List<StatLine> lifetimeLines = lines(page, tallies, eventId, null);
        boolean attended = shown != null
                && tallies.get(AlmanacKeys.season(eventId, shown, AlmanacKeys.ATTENDED)) > 0L;
        long seasonsAttended = tallies.get(AlmanacKeys.lifetime(eventId, AlmanacKeys.ATTENDED));

        List<Keepsake> keepsakes = new ArrayList<>();
        if (engine != null && subject != null) {
            for (Map.Entry<Integer, Achievement> copy : keepsakeCopies(page, engine).entrySet()) {
                if (engine.isUnlocked(subject, copy.getValue().id())) {
                    keepsakes.add(new Keepsake(copy.getValue().id(), copy.getKey(), copy.getValue().icon()));
                }
            }
        }
        SeasonAchievements achievements = achievements(eventId, page, engine, subject);
        return new Detail(season, shown, attended, seasonsAttended, seasonLines, lifetimeLines,
                List.copyOf(keepsakes), achievements == null ? 0 : achievements.earned(),
                achievements == null ? 0 : achievements.total(),
                achievements == null ? List.of() : achievements.feats());
    }

    /**
     * The cross-season banner: of the cross-season achievements in circulation or earned (filed under
     * {@value #SEASONS_CATEGORY} with no subcategory), the lowest not yet earned by its order (then id),
     * else, once every one is earned, the highest. A ladder of rungs therefore climbs one rung at a time.
     * Its count is the engine's tally (a grouped capstone counts seasons, never copies), full once
     * earned; a cross-season achievement that is no capstone carries no count. Null while none is in
     * circulation or earned.
     */
    @Nullable
    public static Banner banner(@Nonnull AchievementEngine engine, @Nonnull Subject subject) {
        List<Achievement> rungs = new ArrayList<>();
        for (Achievement achievement : byId(engine)) {
            if (!SEASONS_CATEGORY.equals(achievement.category()) || achievement.subcategory() != null) {
                continue;
            }
            if (!engine.isUnlocked(subject, achievement.id()) && !achievement.available()) {
                continue;
            }
            rungs.add(achievement);
        }
        if (rungs.isEmpty()) {
            return null;
        }
        rungs.sort(Comparator.comparingInt(Achievement::sortOrder).thenComparing(Achievement::id));
        Achievement shown = rungs.get(rungs.size() - 1);
        for (Achievement rung : rungs) {
            if (!engine.isUnlocked(subject, rung.id())) {
                shown = rung;
                break;
            }
        }
        boolean earned = engine.isUnlocked(subject, shown.id());
        if (!shown.isMeta()) {
            return new Banner(shown.id(), earned, 0, 0);
        }
        AchievementEngine.CriterionTally tally = engine.tally(subject, shown);
        return new Banner(shown.id(), earned, earned ? tally.total() : tally.completed(), tally.total());
    }

    // ==================== the redesigned season page ====================

    /**
     * One season's page for one player, read in {@code requested} scope when that scope is listed (null:
     * the default, today's rule: the year on now, else the last year taken part in, else every season).
     * {@code page} is the season's loaded page (null: no tiles, no keepsakes, no links); a null engine or
     * subject leaves the keepsake and achievement sections absent.
     */
    @Nonnull
    public static SeasonPage page(@Nonnull Season season, @Nullable AlmanacEntryAsset page,
            @Nonnull CounterMap tallies, @Nullable AchievementEngine engine, @Nullable Subject subject,
            @Nullable Scope requested, @Nonnull AlmanacCalendar calendar, @Nonnull ServerTallies server,
            long nowMs) {
        return page(season, page, tallies, engine, subject, requested, calendar, server, nowMs,
                AlmanacView::textureShips, ItemIds::iconPath);
    }

    /** {@link #page} with the texture and item-picture lookups handed in, for a test with no asset store. */
    @Nonnull
    static SeasonPage page(@Nonnull Season season, @Nullable AlmanacEntryAsset page, @Nonnull CounterMap tallies,
            @Nullable AchievementEngine engine, @Nullable Subject subject, @Nullable Scope requested,
            @Nonnull AlmanacCalendar calendar, @Nonnull ServerTallies server, long nowMs,
            @Nonnull Predicate<String> textureShips, @Nonnull Function<String, String> iconPaths) {
        String eventId = season.eventId();
        Dates dates = calendar.dates(eventId, nowMs);
        Timing timing = timing(season, dates, nowMs);
        Map<Integer, Achievement> copies = keepsakeCopies(page, engine);
        Set<Integer> earned = yearsEarned(copies, engine, subject);
        List<YearChip> years = years(season, timing, dates, tallies, earned);
        Scope scope = scope(requested, season, timing, years);
        return new SeasonPage(season, timing, years, scope, tookPartIn(scope, years),
                tallies(page, eventId, tallies, scope, server), keepsakes(page, engine, subject, copies, earned, years),
                achievements(eventId, page, engine, subject), hero(season, page, textureShips, iconPaths),
                page == null ? null : page.accent(), links(page),
                sections(eventId, page, tallies, textureShips, iconPaths));
    }

    /** When {@code season} runs, as the calendar knows it at {@code nowMs}: its chip, row line and dates. */
    @Nonnull
    public static Timing timing(@Nonnull Season season, @Nonnull AlmanacCalendar calendar, long nowMs) {
        return timing(season, calendar.dates(season.eventId(), nowMs), nowMs);
    }

    @Nonnull
    static Timing timing(@Nonnull Season season, @Nonnull Dates dates, long nowMs) {
        ZoneId zone = dates.zone();
        LocalDate today = day(nowMs, zone);
        Occurrence live = dates.live();
        Occurrence next = dates.next();
        Occurrence framing = live != null ? live : next != null ? next : last(dates.history());
        MonthDay windowStart = framing == null ? null : MonthDay.from(day(framing.startMs(), zone));
        MonthDay windowEnd = framing == null ? null : MonthDay.from(lastDay(framing, zone));
        LocalDate nextStart = next == null ? null : day(next.startMs(), zone);
        Integer windowYear = dates.datesMove() && framing != null ? Integer.valueOf(framing.year()) : null;
        Recurring recurring = dates.recurrence() == null ? null : new Recurring(dates.recurrence(),
                next == null ? null : moment(next.startMs(), zone), next == null ? null : moment(next.endMs(), zone));
        if (live != null || season.live()) {
            Integer daysLeft = null;
            boolean lastDay = false;
            if (live != null && live.contains(nowMs)) {
                LocalDate last = lastDay(live, zone);
                daysLeft = (int) Math.max(1L, ChronoUnit.DAYS.between(today, last) + 1L);
                lastDay = !today.isBefore(last);
            }
            return new Timing(true, daysLeft, lastDay, null, false, windowStart, windowEnd, nextStart, false,
                    windowYear, recurring);
        }
        Integer daysUntil = nextStart == null ? null : (int) Math.max(0L, ChronoUnit.DAYS.between(today, nextStart));
        boolean soon = daysUntil != null && daysUntil <= SOON_DAYS;
        boolean startsLater = next != null && dates.history().isEmpty();
        return new Timing(false, null, false, daysUntil, soon, windowStart, windowEnd, nextStart, startsLater,
                windowYear, recurring);
    }

    /**
     * Every year a player can read, oldest first: each run the calendar has had, every year the player
     * holds a tally for, and the run on now, each with the runs attended in it. None while the first run is
     * still ahead.
     */
    @Nonnull
    static List<YearChip> years(@Nonnull Season season, @Nonnull Timing timing, @Nonnull Dates dates,
            @Nonnull CounterMap tallies, @Nonnull Set<Integer> keepsakeYearsEarned) {
        if (timing.startsLater()) {
            return List.of();
        }
        SortedSet<Integer> tookPart = AlmanacKeys.seasonYears(tallies, season.eventId());
        TreeSet<Integer> years = new TreeSet<>(tookPart);
        for (Occurrence run : dates.history()) {
            years.add(run.year());
        }
        Integer liveYear = dates.live() != null ? Integer.valueOf(dates.live().year())
                : season.live() && season.liveYear() > 0 ? Integer.valueOf(season.liveYear()) : null;
        if (liveYear != null) {
            years.add(liveYear);
        }
        List<YearChip> out = new ArrayList<>();
        for (int year : years) {
            int runs = (int) Math.min(Integer.MAX_VALUE, AlmanacKeys.runsAttended(tallies, season.eventId(), year));
            out.add(new YearChip(year, liveYear != null && year == liveYear, tookPart.contains(year),
                    keepsakeYearsEarned.contains(year), runs));
        }
        return List.copyOf(out);
    }

    /**
     * The scope a page reads: the one asked for when it is listed (Every season always is, while any year
     * is); else the year on now; else the last year the player took part in; else every season.
     */
    @Nonnull
    static Scope scope(@Nullable Scope requested, @Nonnull Season season, @Nonnull Timing timing,
            @Nonnull List<YearChip> years) {
        if (years.isEmpty()) {
            return Scope.EVERY;
        }
        if (requested != null) {
            if (requested.every()) {
                return Scope.EVERY;
            }
            for (YearChip chip : years) {
                if (chip.year() == requested.year()) {
                    return requested;
                }
            }
        }
        YearChip lastTookPart = null;
        for (YearChip chip : years) {
            if (chip.live()) {
                return new Scope(chip.year());
            }
            if (chip.tookPart()) {
                lastTookPart = chip;
            }
        }
        return lastTookPart == null ? Scope.EVERY : new Scope(lastTookPart.year());
    }

    /** Did the player take part in {@code scope}: that year, or any year for every season? */
    static boolean tookPartIn(@Nonnull Scope scope, @Nonnull List<YearChip> years) {
        for (YearChip chip : years) {
            if (chip.tookPart() && (scope.every() || chip.year() == scope.year())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The season's tiles in {@code scope}, by Order then name; a tile nothing counted reads 0. A null
     * {@code server} reads no server line (a surface that shows only the player's own figures).
     */
    @Nonnull
    public static List<Tally> tallies(@Nullable AlmanacEntryAsset page, @Nonnull String eventId,
            @Nonnull CounterMap tallies, @Nonnull Scope scope, @Nullable ServerTallies server) {
        List<Tally> out = new ArrayList<>();
        for (Map.Entry<String, AlmanacStatAsset> stat : orderedStats(page)) {
            String statId = stat.getKey();
            String lifetimeKey = AlmanacKeys.lifetime(eventId, statId);
            long lifetime = tallies.get(lifetimeKey);
            String scopedKey = scope.every() ? lifetimeKey : AlmanacKeys.season(eventId, scope.year(), statId);
            long serverTotal = server == null ? 0L : server.get(scopedKey);
            out.add(new Tally(statId, stat.getValue().getTextKey(), stat.getValue().getIcon(),
                    scope.every() ? lifetime : tallies.get(scopedKey), scope.every() ? null : Long.valueOf(lifetime),
                    serverTotal > 0L ? Long.valueOf(serverTotal) : null));
        }
        return List.copyOf(out);
    }

    /**
     * The season's achievements: those filed under {@code Seasons > <event>}, its keepsake copies apart.
     * Null (the section absent) with no engine or subject, and when nothing is filed or earned there.
     */
    @Nullable
    static SeasonAchievements achievements(@Nonnull String eventId, @Nullable AlmanacEntryAsset page,
            @Nullable AchievementEngine engine, @Nullable Subject subject) {
        if (engine == null || subject == null) {
            return null;
        }
        Set<String> keepsakeIds = new TreeSet<>();
        for (Achievement copy : keepsakeCopies(page, engine).values()) {
            keepsakeIds.add(copy.id());
        }
        List<Feat> feats = new ArrayList<>();
        int earned = 0;
        int listed = 0;
        for (Achievement achievement : byId(engine)) {
            if (!SEASONS_CATEGORY.equals(achievement.category()) || !eventId.equals(achievement.subcategory())
                    || keepsakeIds.contains(achievement.id())) {
                continue;
            }
            boolean unlocked = engine.isUnlocked(subject, achievement.id());
            if (achievement.featOfStrength()) {
                if (unlocked) {
                    feats.add(new Feat(achievement.id(), achievement.icon()));
                }
                continue;
            }
            if (engine.isVisible(subject, achievement)) {
                listed++;
                if (unlocked) {
                    earned++;
                }
            }
        }
        return listed == 0 && feats.isEmpty() ? null : new SeasonAchievements(earned, listed, List.copyOf(feats));
    }

    /** The season's links that have words and somewhere this server can open, in the order written. */
    @Nonnull
    static List<SeasonLink> links(@Nullable AlmanacEntryAsset page) {
        if (page == null) {
            return List.of();
        }
        List<SeasonLink> out = new ArrayList<>();
        for (AlmanacLinkAsset link : page.links()) {
            String textKey = link.textKey();
            Destination destination = link.destination();
            if (textKey != null && destination != null) {
                out.add(new SeasonLink(textKey, destination));
            }
        }
        return List.copyOf(out);
    }

    // ==================== the body's sections ====================

    /**
     * The body's parts in the order drawn. With no page, or a page that writes no {@code Sections}: today's
     * order, Tallies, Keepsakes, Achievements (its button into the book resolved), Links. Else the first
     * {@link #SECTIONS_MAX} entries in the order written: an entry naming no part is skipped, a built-in part
     * already drawn is skipped, and a banner or a collection with nothing it can draw is skipped. Which
     * built-in parts have anything to show is the plan's question, not this one.
     */
    @Nonnull
    static List<Section> sections(@Nonnull String eventId, @Nullable AlmanacEntryAsset page,
            @Nonnull CounterMap tallies, @Nonnull Predicate<String> textureShips,
            @Nonnull Function<String, String> iconPaths) {
        List<AlmanacSectionAsset> authored = page == null ? null : page.sections();
        if (authored == null) {
            return List.of(new TalliesSection(), new KeepsakesSection(), callToAction(eventId, null),
                    new LinksSection());
        }
        String accent = page.accent();
        Set<String> drawn = new HashSet<>();
        List<Section> out = new ArrayList<>();
        for (int i = 0; i < authored.size() && i < SECTIONS_MAX; i++) {
            AlmanacSectionAsset entry = authored.get(i);
            String part = entry.part();
            if (part == null) {
                continue;
            }
            Section section;
            if (AlmanacSectionAsset.BANNER.equals(part)) {
                section = banner(entry.banner(), accent, textureShips, iconPaths);
            } else if (AlmanacSectionAsset.COLLECTION.equals(part)) {
                section = collection(entry.collection(), tallies, iconPaths);
            } else if (!drawn.add(part)) {
                section = null;
            } else if (AlmanacSectionAsset.ACHIEVEMENTS.equals(part)) {
                section = callToAction(eventId, entry.achievements());
            } else if (AlmanacSectionAsset.TALLIES.equals(part)) {
                section = new TalliesSection();
            } else if (AlmanacSectionAsset.KEEPSAKES.equals(part)) {
                section = new KeepsakesSection();
            } else {
                section = new LinksSection();
            }
            if (section != null) {
                out.add(section);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The achievements section with its button into the book: none when the section says
     * {@code ShowButton: false}; else where its {@code Button} sends it, else the book opened on this season
     * ({@link #seasonAchievements}), with the {@code Button}'s words when it writes them (null: the
     * library's). With nowhere to go, no button.
     */
    @Nonnull
    static AchievementsSection callToAction(@Nonnull String eventId, @Nullable AlmanacAchievementsAsset authored) {
        if (authored != null && !authored.showButton()) {
            return new AchievementsSection(null, null);
        }
        Destination written = authored == null ? null : authored.buttonDestination();
        Destination destination = written != null ? written : seasonAchievements(eventId);
        if (destination == null) {
            return new AchievementsSection(null, null);
        }
        return new AchievementsSection(destination, authored == null ? null : authored.buttonTextKey());
    }

    /**
     * The book opened on {@code eventId}'s achievements, {@code { "Type": "Achievements", "Category":
     * "seasons", "Subcategory": "<event id>" }}, decoded through the shared vocabulary at run time so the
     * Almanac needs no edge to the book's module. Null when no installed mod registers the book's type, or
     * the decode fails.
     */
    @Nullable
    public static Destination seasonAchievements(@Nonnull String eventId) {
        if (!Destinations.isRegistered(ACHIEVEMENTS_TYPE)) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty(Destination.TYPE_KEY, ACHIEVEMENTS_TYPE);
        json.addProperty(BOOK_CATEGORY_KEY, SEASONS_CATEGORY);
        json.addProperty(BOOK_SUBCATEGORY_KEY, AlmanacKeys.normalize(eventId));
        try {
            return Destination.CODEC.decodeJson(RawJsonReader.fromJsonString(json.toString()), new ExtraInfo());
        } catch (Throwable t) {
            SafeLog.fine("[almanac] the button into the book could not be built for '" + eventId + "': "
                    + t.getMessage());
            return null;
        }
    }

    /**
     * An inline banner on its own plate, {@link #BANNER_WIDTH} wide and its {@code Height} tall (kept to
     * {@link #BANNER_MIN} through {@link #BANNER_MAX}). Its art when it ships; else its composition on the
     * banner's plate (a colour band when no item draws); else a plate in the season's accent, darkened. Null
     * when it has no picture of its own and no title, line or button either.
     */
    @Nullable
    static BannerSection banner(@Nullable AlmanacBannerAsset authored, @Nullable String accent,
            @Nonnull Predicate<String> textureShips, @Nonnull Function<String, String> iconPaths) {
        if (authored == null) {
            return null;
        }
        int height = clamp(authored.height(), BANNER_HEIGHT, BANNER_MIN, BANNER_MAX);
        SeasonLink button = seasonLink(authored.button());
        String art = authored.art();
        if (art != null && ships(art, textureShips)) {
            return new BannerSection(art, null, height, authored.titleKey(), authored.flavorKey(), button);
        }
        HeroComposition composition = composition(authored.composition(), accent, textureShips, iconPaths,
                BANNER_WIDTH, height, true);
        if (composition == null && authored.titleKey() == null && authored.flavorKey() == null && button == null) {
            return null;
        }
        return new BannerSection(null, composition != null ? composition
                : new HeroComposition(darken(accent), null, null, null, List.of()), height, authored.titleKey(),
                authored.flavorKey(), button);
    }

    /**
     * A collection's grid for one player: the first {@link #COLLECTION_MAX_ITEMS} slots written, in order (the
     * rule {@code COLLECTION_ITEMS_OVER_CAP} counts by), each item once (matched without case), each the server has
     * a picture for, each owned when the player has had it once. A repeat or an item the server lacks among those
     * slots draws nothing and gives its place to no later slot. Null when no item draws.
     */
    @Nullable
    static CollectionSection collection(@Nullable AlmanacCollectionAsset authored, @Nonnull CounterMap tallies,
            @Nonnull Function<String, String> iconPaths) {
        if (authored == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        List<CollectionItem> items = new ArrayList<>();
        List<AlmanacCollectionAsset.Slot> slots = authored.slots();
        for (int i = 0; i < slots.size() && i < COLLECTION_MAX_ITEMS; i++) {
            AlmanacCollectionAsset.Slot slot = slots.get(i);
            String itemId = slot.item();
            if (!seen.add(AlmanacKeys.normalize(itemId))) {
                continue;
            }
            String icon = iconPath(itemId, iconPaths);
            if (icon == null) {
                continue;
            }
            items.add(new CollectionItem(itemId, icon, AlmanacCollection.owned(tallies, itemId), slot.hidden(),
                    slot.sourceKey()));
        }
        if (items.isEmpty()) {
            return null;
        }
        return new CollectionSection(authored.titleKey(), authored.flavorKey(), List.copyOf(items),
                seasonLink(authored.button()));
    }

    /** A usable authored button as the view's link, or null. */
    @Nullable
    private static SeasonLink seasonLink(@Nullable AlmanacLinkAsset link) {
        if (link == null || link.textKey() == null || link.destination() == null) {
            return null;
        }
        return new SeasonLink(link.textKey(), link.destination());
    }

    /**
     * Every yearly copy of the season's keepsake in the catalogue, by year: an achievement minted for an
     * occurrence of the page's {@code Keepsake} base id, else one whose id is {@code <Keepsake>_<yyyy>}.
     * Empty for a season with no keepsake or no catalogue.
     */
    @Nonnull
    static Map<Integer, Achievement> keepsakeCopies(@Nullable AlmanacEntryAsset page,
            @Nullable AchievementEngine engine) {
        if (page == null || page.getKeepsake() == null || engine == null) {
            return Map.of();
        }
        String base = AlmanacKeys.normalize(page.getKeepsake());
        Map<Integer, Achievement> out = new TreeMap<>();
        for (Achievement achievement : byId(engine)) {
            Achievement.Occurrence occurrence = achievement.occurrence();
            int year = occurrence != null
                    ? (base.equals(occurrence.baseId()) ? occurrence.year() : 0)
                    : mintYear(achievement.id(), base + "_");
            if (year > 0) {
                out.putIfAbsent(year, achievement);
            }
        }
        return out;
    }

    /** The years among {@code copies} the subject has earned. */
    @Nonnull
    static Set<Integer> yearsEarned(@Nonnull Map<Integer, Achievement> copies, @Nullable AchievementEngine engine,
            @Nullable Subject subject) {
        if (engine == null || subject == null) {
            return Set.of();
        }
        Set<Integer> out = new TreeSet<>();
        for (Map.Entry<Integer, Achievement> copy : copies.entrySet()) {
            if (engine.isUnlocked(subject, copy.getValue().id())) {
                out.add(copy.getKey());
            }
        }
        return out;
    }

    /**
     * The keepsake shelf: a tile per year the player can read (and any year a copy was earned), oldest
     * first; earned, still to earn for the run on now, else missed. Null (the section absent) for a season
     * with no keepsake in the catalogue, with no subject loaded, and while its first run is still ahead.
     */
    @Nullable
    static List<YearKeepsake> keepsakes(@Nullable AlmanacEntryAsset page, @Nullable AchievementEngine engine,
            @Nullable Subject subject, @Nonnull Map<Integer, Achievement> copies, @Nonnull Set<Integer> earned,
            @Nonnull List<YearChip> years) {
        if (page == null || page.getKeepsake() == null || engine == null || subject == null || copies.isEmpty()) {
            return null;
        }
        TreeSet<Integer> shelf = new TreeSet<>(earned);
        Integer liveYear = null;
        for (YearChip chip : years) {
            shelf.add(chip.year());
            if (chip.live()) {
                liveYear = chip.year();
            }
        }
        if (shelf.isEmpty()) {
            return null;
        }
        String anyIcon = null;
        for (Achievement copy : copies.values()) {
            if (copy.icon() != null) {
                anyIcon = copy.icon();
                break;
            }
        }
        List<YearKeepsake> out = new ArrayList<>();
        for (int year : shelf) {
            Achievement copy = copies.get(year);
            KeepsakeState state = earned.contains(year) ? KeepsakeState.EARNED
                    : liveYear != null && liveYear == year ? KeepsakeState.TO_EARN : KeepsakeState.MISSED;
            out.add(new YearKeepsake(year, state, copy == null ? null : copy.id(),
                    copy != null && copy.icon() != null ? copy.icon() : anyIcon));
        }
        return List.copyOf(out);
    }

    /**
     * Which top a season's page draws. In order: the {@code Hero.Art} while {@code ShowArt} is true and
     * the texture ships; else the {@code Hero.Composition} when at least one of its items is one the server
     * has; else the season's own icon. A composition's items are kept on the plate (side 24 to 128, then
     * the corner clamped so the whole picture shows), unknown ones skipped, the first twelve drawn.
     */
    @Nonnull
    static Hero hero(@Nonnull Season season, @Nullable AlmanacEntryAsset page, @Nonnull Predicate<String> textureShips,
            @Nonnull Function<String, String> iconPaths) {
        String ownIcon = iconPath(season.icon(), iconPaths);
        AlmanacHeroAsset authored = page == null ? null : page.hero();
        if (authored == null) {
            return new Hero(null, null, ownIcon);
        }
        String art = authored.art();
        if (authored.showArt() && art != null && ships(art, textureShips)) {
            return new Hero(art, null, ownIcon);
        }
        HeroComposition composition = composition(authored.composition(), page.accent(), textureShips, iconPaths);
        return new Hero(null, composition, ownIcon);
    }

    @Nullable
    private static HeroComposition composition(@Nullable AlmanacHeroAsset.Composition authored,
            @Nullable String accent, @Nonnull Predicate<String> textureShips,
            @Nonnull Function<String, String> iconPaths) {
        return composition(authored, accent, textureShips, iconPaths, HERO_WIDTH, HERO_HEIGHT, false);
    }

    /**
     * {@code authored} read onto a {@code plateWidth} x {@code plateHeight} plate: each item's side kept to 24
     * through the smaller of 128 and the plate's height, its corner kept so the whole picture shows, unknown
     * items skipped, the first twelve drawn. With no item drawn it is null unless {@code keepEmpty} (a banner's
     * colour band); the hero's top needs at least one.
     */
    @Nullable
    private static HeroComposition composition(@Nullable AlmanacHeroAsset.Composition authored,
            @Nullable String accent, @Nonnull Predicate<String> textureShips,
            @Nonnull Function<String, String> iconPaths, int plateWidth, int plateHeight, boolean keepEmpty) {
        if (authored == null) {
            return null;
        }
        int sideMax = Math.min(HERO_ITEM_MAX, plateHeight);
        List<HeroItem> items = new ArrayList<>();
        for (AlmanacHeroAsset.Placement placement : authored.items()) {
            if (items.size() >= HERO_MAX_ITEMS) {
                break;
            }
            String icon = iconPath(placement.item(), iconPaths);
            if (icon == null) {
                continue;
            }
            int size = clamp(placement.size(), HERO_ITEM_SIZE, HERO_ITEM_MIN, sideMax);
            items.add(new HeroItem(placement.item(), icon, clamp(placement.x(), 0, 0, plateWidth - size),
                    clamp(placement.y(), 0, 0, plateHeight - size), size));
        }
        if (items.isEmpty() && !keepEmpty) {
            return null;
        }
        AlmanacHeroAsset.Gradient sky = authored.gradient();
        HeroGradient gradient = sky == null ? null : new HeroGradient(sky.top(), sky.bottom());
        String background = gradient != null ? null
                : authored.background() != null ? authored.background() : darken(accent);
        String texture = authored.backgroundTexture();
        AlmanacHeroAsset.Glow light = authored.glow();
        HeroGlow glow = light == null ? null : new HeroGlow(light.color(),
                clamp(light.x(), 0, GLOW_X_MIN, GLOW_X_MAX), clamp(light.y(), 0, GLOW_Y_MIN, GLOW_Y_MAX),
                clamp(light.size(), GLOW_SIZE, GLOW_MIN, GLOW_MAX));
        return new HeroComposition(background, texture != null && ships(texture, textureShips) ? texture : null,
                gradient, glow, List.copyOf(items));
    }

    /** The record card across every listed season: seasons taken part in, and keepsakes earned. */
    @Nonnull
    public static Record record(@Nonnull List<Season> seasons, @Nonnull Map<String, AlmanacEntryAsset> pages,
            @Nonnull CounterMap tallies, @Nullable AchievementEngine engine, @Nullable Subject subject) {
        long seasonsTakenPart = 0L;
        long keepsakes = 0L;
        for (Season season : seasons) {
            seasonsTakenPart += tallies.get(AlmanacKeys.lifetime(season.eventId(), AlmanacKeys.ATTENDED));
            keepsakes += yearsEarned(keepsakeCopies(pages.get(season.eventId()), engine), engine, subject).size();
        }
        return new Record(seasonsTakenPart, keepsakes);
    }

    /**
     * The year at a glance: twelve months, January first, each with the seasons running in it, in list
     * order. A season is marked on every month its run touches (the run on now, else the next, else the
     * last), a run crossing the new year marking December and January; one with no dates marks none.
     */
    @Nonnull
    public static List<MonthMarks> yearAtAGlance(@Nonnull List<Season> seasons, @Nonnull AlmanacCalendar calendar,
            long nowMs) {
        List<List<String>> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            months.add(new ArrayList<>());
        }
        for (Season season : seasons) {
            Dates dates = calendar.dates(season.eventId(), nowMs);
            Occurrence run = dates.live() != null ? dates.live() : dates.next() != null ? dates.next()
                    : last(dates.history());
            if (run == null) {
                continue;
            }
            YearMonth month = YearMonth.from(day(run.startMs(), dates.zone()));
            YearMonth end = YearMonth.from(lastDay(run, dates.zone()));
            for (int i = 0; i < 12 && !month.isAfter(end); i++, month = month.plusMonths(1)) {
                List<String> marks = months.get(month.getMonthValue() - 1);
                if (!marks.contains(season.eventId())) {
                    marks.add(season.eventId());
                }
            }
        }
        List<MonthMarks> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            out.add(new MonthMarks(i + 1, List.copyOf(months.get(i))));
        }
        return List.copyOf(out);
    }

    // ==================== helpers ====================

    @Nonnull
    private static List<StatLine> lines(@Nullable AlmanacEntryAsset page, @Nonnull CounterMap tallies,
            @Nonnull String eventId, @Nullable Integer year) {
        List<StatLine> out = new ArrayList<>();
        for (Map.Entry<String, AlmanacStatAsset> stat : orderedStats(page)) {
            String key = year == null
                    ? AlmanacKeys.lifetime(eventId, stat.getKey()) : AlmanacKeys.season(eventId, year, stat.getKey());
            out.add(new StatLine(stat.getKey(), stat.getValue().getTextKey(), stat.getValue().getIcon(), tallies.get(key)));
        }
        return List.copyOf(out);
    }

    /** The page's usable stat lines, keyed by their normalized id, by Order then id. */
    @Nonnull
    private static List<Map.Entry<String, AlmanacStatAsset>> orderedStats(@Nullable AlmanacEntryAsset page) {
        if (page == null) {
            return List.of();
        }
        List<Map.Entry<String, AlmanacStatAsset>> stats = new ArrayList<>(page.getStats().entrySet());
        stats.sort(Comparator.comparingInt((Map.Entry<String, AlmanacStatAsset> e) -> e.getValue().orderOrLast())
                .thenComparing(e -> AlmanacKeys.normalize(e.getKey())));
        List<Map.Entry<String, AlmanacStatAsset>> out = new ArrayList<>();
        for (Map.Entry<String, AlmanacStatAsset> stat : stats) {
            String statId = AlmanacKeys.normalize(stat.getKey());
            if (AlmanacKeys.usableId(statId) && !stat.getValue().isBlank()) {
                out.add(Map.entry(statId, stat.getValue()));
            }
        }
        return out;
    }

    /** The catalogue in id order, so every read walks it the same way. */
    @Nonnull
    private static List<Achievement> byId(@Nonnull AchievementEngine engine) {
        List<Achievement> all = new ArrayList<>(engine.achievements());
        all.sort(Comparator.comparing(Achievement::id));
        return all;
    }

    /** The year a minted keepsake id names ({@code <prefix><yyyy>}), or 0 when it is not one. */
    static int mintYear(@Nonnull String id, @Nonnull String mintPrefix) {
        if (!id.startsWith(mintPrefix) || id.length() != mintPrefix.length() + 4) {
            return 0;
        }
        int year = 0;
        for (int i = mintPrefix.length(); i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            year = year * 10 + (c - '0');
        }
        return year;
    }

    @Nonnull
    private static LocalDate day(long ms, @Nonnull ZoneId zone) {
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
    }

    /** {@code ms} as the day and time of day on {@code zone}'s clock. */
    @Nonnull
    private static LocalDateTime moment(long ms, @Nonnull ZoneId zone) {
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime();
    }

    /**
     * A run's last day: the day holding its last instant. For a run ending at midnight that is the day before its
     * end; a run ending at a time of day (a two-hour contest, a Friday 18:00 to Sunday 18:00 weekend) ends that day.
     */
    @Nonnull
    private static LocalDate lastDay(@Nonnull Occurrence run, @Nonnull ZoneId zone) {
        return day(Math.max(run.startMs(), run.endMs() - 1L), zone);
    }

    @Nullable
    private static Occurrence last(@Nonnull List<Occurrence> runs) {
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }

    /** {@code itemId}'s own picture, or null for no id, an item the server lacks, or a failing lookup. */
    @Nullable
    private static String iconPath(@Nullable String itemId, @Nonnull Function<String, String> iconPaths) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        try {
            String path = iconPaths.apply(itemId.trim());
            return path == null || path.isBlank() ? null : path;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Does the game ship this Common-rooted texture, or its {@code @2x} twin? The engine's own rule for a UI
     * picture ({@code CommonAssetValidator}: a missing {@code X.png} is fine when {@code X@2x.png} ships).
     * False in a JVM with no asset registry.
     */
    static boolean textureShips(@Nonnull String path) {
        try {
            String unix = path.replace('\\', '/');
            return CommonAssetRegistry.hasCommonAsset(unix) || (unix.endsWith(".png")
                    && CommonAssetRegistry.hasCommonAsset(unix.substring(0, unix.length() - 4) + "@2x.png"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean ships(@Nonnull String texture, @Nonnull Predicate<String> textureShips) {
        try {
            return textureShips.test(texture);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int clamp(@Nullable Integer value, int unauthored, int min, int max) {
        int v = value == null ? unauthored : value;
        return Math.max(min, Math.min(max, v));
    }

    /** {@code hex} at {@link #DERIVED_BACKGROUND_SHARE} of its brightness, or null for no colour. */
    @Nullable
    static String darken(@Nullable String hex) {
        if (hex == null || hex.length() != 7) {
            return null;
        }
        try {
            int rgb = Integer.parseInt(hex.substring(1), 16);
            int r = (int) Math.round(((rgb >> 16) & 0xff) * DERIVED_BACKGROUND_SHARE);
            int g = (int) Math.round(((rgb >> 8) & 0xff) * DERIVED_BACKGROUND_SHARE);
            int b = (int) Math.round((rgb & 0xff) * DERIVED_BACKGROUND_SHARE);
            return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
