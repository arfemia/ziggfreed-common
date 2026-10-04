package com.ziggfreed.common.almanac.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.almanac.AlmanacCalendar;
import com.ziggfreed.common.almanac.AlmanacKeys;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacStatAsset;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.subject.Subject;

/**
 * What the Almanac shows, as plain data: which seasons it lists, what one season's page says for one
 * player, and the cross-season banner. Every decision the page makes lives here, where a test can
 * reach it; the page only paints.
 *
 * <p><b>Achievements are found by where content files them.</b> A season's own are filed under
 * {@value #SEASONS_CATEGORY} with the event id as subcategory; a cross-season one (Seasons of Orbis)
 * under {@value #SEASONS_CATEGORY} with no subcategory; a keepsake is the catalogue's
 * {@code <Keepsake>_<yyyy>}, wherever it is filed.
 */
public final class AlmanacView {

    /** The category every season's achievements file under. */
    public static final String SEASONS_CATEGORY = "seasons";

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

    /** The cross-season achievement shown above the list, with its meta-children progress. */
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

    private AlmanacView() {
    }

    /** Every season to list: each page whose event the calendar answers for, by Order, then id. */
    @Nonnull
    public static List<Season> seasons(@Nonnull Map<String, AlmanacEntryAsset> pages,
            @Nonnull AlmanacCalendar calendar) {
        List<Map.Entry<String, AlmanacEntryAsset>> ordered = new ArrayList<>(pages.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, AlmanacEntryAsset> e) -> e.getValue().orderOrLast())
                .thenComparing(e -> AlmanacKeys.normalize(e.getKey())));
        List<Season> out = new ArrayList<>();
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
            out.add(new Season(eventId, page.titleKey(), page.flavorKey(), page.getIcon(), state.live(),
                    state.live() ? state.year() : 0));
        }
        return List.copyOf(out);
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
        List<Feat> feats = new ArrayList<>();
        int earned = 0;
        int listed = 0;
        if (engine != null && subject != null) {
            String mintPrefix = page == null || page.getKeepsake() == null
                    ? null : AlmanacKeys.normalize(page.getKeepsake()) + "_";
            for (Achievement achievement : byId(engine)) {
                String id = achievement.id();
                boolean unlocked = engine.isUnlocked(subject, id);
                int mintYear = mintPrefix == null ? 0 : mintYear(id, mintPrefix);
                if (mintYear > 0) {
                    if (unlocked) {
                        keepsakes.add(new Keepsake(id, mintYear, achievement.icon()));
                    }
                    continue;
                }
                if (!SEASONS_CATEGORY.equals(achievement.category()) || !eventId.equals(achievement.subcategory())) {
                    continue;
                }
                if (achievement.featOfStrength()) {
                    if (unlocked) {
                        feats.add(new Feat(id, achievement.icon()));
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
        }
        keepsakes.sort(Comparator.comparingInt(Keepsake::year));
        return new Detail(season, shown, attended, seasonsAttended, seasonLines, lifetimeLines,
                List.copyOf(keepsakes), earned, listed, List.copyOf(feats));
    }

    /** The first cross-season achievement in circulation or earned, or null: absent while it is off. */
    @Nullable
    public static Banner banner(@Nonnull AchievementEngine engine, @Nonnull Subject subject) {
        for (Achievement achievement : byId(engine)) {
            if (!SEASONS_CATEGORY.equals(achievement.category()) || achievement.subcategory() != null) {
                continue;
            }
            boolean earned = engine.isUnlocked(subject, achievement.id());
            if (!earned && !achievement.available()) {
                continue;
            }
            int childrenEarned = 0;
            for (String child : achievement.metaChildren()) {
                if (engine.isUnlocked(subject, child)) {
                    childrenEarned++;
                }
            }
            return new Banner(achievement.id(), earned, childrenEarned, achievement.metaChildren().size());
        }
        return null;
    }

    @Nonnull
    private static List<StatLine> lines(@Nullable AlmanacEntryAsset page, @Nonnull CounterMap tallies,
            @Nonnull String eventId, @Nullable Integer year) {
        if (page == null) {
            return List.of();
        }
        List<Map.Entry<String, AlmanacStatAsset>> stats = new ArrayList<>(page.getStats().entrySet());
        stats.sort(Comparator.comparingInt((Map.Entry<String, AlmanacStatAsset> e) -> e.getValue().orderOrLast())
                .thenComparing(e -> AlmanacKeys.normalize(e.getKey())));
        List<StatLine> out = new ArrayList<>();
        for (Map.Entry<String, AlmanacStatAsset> stat : stats) {
            String statId = AlmanacKeys.normalize(stat.getKey());
            if (!AlmanacKeys.usableId(statId) || stat.getValue().isBlank()) {
                continue;
            }
            String key = year == null
                    ? AlmanacKeys.lifetime(eventId, statId) : AlmanacKeys.season(eventId, year, statId);
            out.add(new StatLine(statId, stat.getValue().getTextKey(), stat.getValue().getIcon(), tallies.get(key)));
        }
        return List.copyOf(out);
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
}
