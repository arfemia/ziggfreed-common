package com.ziggfreed.common.achievement.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * How one achievement file that names an {@code Occurrence} becomes one achievement per yearly
 * occurrence of its calendar event.
 *
 * <p>A copy is an ordinary achievement under its own id, {@code <file id>_<year>}, so everything keyed
 * by an id (criterion progress, a pin, an earned record, a one-winner claim) is bound to its year with
 * no new storage. What makes it yearly is two LIVE readings of the calendar, asked on every look
 * rather than frozen at the fold: it is in circulation only while its own year's occurrence is live,
 * and it reads as a feat once that occurrence is not, so afterwards it is a trophy its earners keep
 * and nobody else can earn.
 *
 * <p>The calendar is read through zc-core's occurrence slot ({@link OccurrenceReader}), which the
 * calendar module fills; this module never sees that module. A calendar that throws reads as closed,
 * reported once per event: an availability read runs on every dispatch and must never take one down.
 */
final class OccurrenceMinting {

    /**
     * What one copy is: its minted id, the occurrence it belongs to, the calendar it reads, and how
     * an explicit child id of its file reads for its year.
     */
    record Mint(@Nonnull String id, @Nonnull String eventId, int year, @Nonnull String baseId,
                @Nonnull OccurrenceReader calendar, @Nonnull UnaryOperator<String> childRewrite) {

        /** The occurrence the runtime object names. */
        @Nonnull
        Achievement.Occurrence occurrence() {
            return new Achievement.Occurrence(eventId, year, baseId);
        }

        /** In circulation while the file is enabled AND this year's occurrence is live, asked on every look. */
        @Nonnull
        BooleanSupplier availability(boolean enabled) {
            return () -> enabled && live(calendar, eventId, year);
        }

        /** A feat when the file says so, and whenever this year's occurrence is not live. */
        @Nonnull
        BooleanSupplier featOfStrength(boolean authoredFeat) {
            return () -> authoredFeat || !live(calendar, eventId, year);
        }

        /** Text arguments with the year sentinel answered by this copy's year, as raw text. */
        @Nonnull
        List<String> args(@Nonnull List<String> authored) {
            List<String> out = new ArrayList<>(authored.size());
            for (String arg : authored) {
                out.add(isYearSentinel(arg) ? String.valueOf(year) : arg);
            }
            return out;
        }

        /** Rewards with every parameter whose whole value is the year sentinel set to this copy's year. */
        @Nonnull
        List<RewardSpec> rewards(@Nonnull List<RewardSpec> authored) {
            List<RewardSpec> out = new ArrayList<>(authored.size());
            for (RewardSpec spec : authored) {
                RewardSpec copy = spec;
                for (Map.Entry<String, String> param : spec.params().entrySet()) {
                    if (isYearSentinel(param.getValue())) {
                        copy = copy.with(param.getKey(), String.valueOf(year));
                    }
                }
                out.add(copy);
            }
            return out;
        }

        /** An explicit child id as this copy reads it. */
        @Nonnull
        String child(@Nonnull String childId) {
            return childRewrite.apply(childId);
        }
    }

    /** The most yearly copies one file mints, so a mistyped first year cannot mint thousands. */
    static final int MAX_YEARS = 100;

    /** The events whose calendar failure was already reported, so a broken calendar logs once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private OccurrenceMinting() {
    }

    /**
     * Every copy {@code asset} mints, oldest year first: one per year from the event's first year
     * through next year, so the copy for a year about to start already exists, out of circulation,
     * when its occurrence opens. A year whose id an authored file already uses yields to that file.
     */
    @Nonnull
    static List<AchievementDefinition> mintAll(@Nonnull AchievementAsset asset, @Nonnull String baseId,
            @Nonnull AchievementAsset.Occurrence occurrence, @Nonnull Map<String, AchievementAsset> assets,
            @Nonnull OccurrenceReader calendar, @Nonnull List<Finding> issues) {
        String eventId = occurrence.eventIdOrNull();
        if (eventId == null) {
            issues.add(Finding.error(AchievementPoolValidator.DOMAIN, "OCCURRENCE_WITHOUT_EVENT",
                    "Occurrence names no Event, so nothing says which yearly event this belongs to and no"
                            + " copy of it exists; write the calendar event's id", baseId));
            return List.of();
        }
        List<AchievementDefinition> out = new ArrayList<>();
        for (int year : years(eventId, calendar, baseId, issues)) {
            String id = mintedId(baseId, year);
            if (assets.containsKey(id)) {
                issues.add(Finding.error(AchievementPoolValidator.DOMAIN, "MINTED_ID_CLASH",
                        "the " + year + " copy would be '" + id + "', which an authored file already is, so"
                                + " the file stands and that year has no copy; rename one of them", baseId));
                continue;
            }
            out.add(asset.toDefinition(new Mint(id, eventId, year, baseId, calendar,
                    siblingRewrite(assets, eventId, year))));
        }
        return out;
    }

    /**
     * The years to mint for {@code eventId}, oldest first; empty when the calendar cannot say. Both
     * year questions answer for an event its owner switched off, so its copies stay minted and what a
     * player earned of them stays theirs; only their circulation closes.
     */
    @Nonnull
    static List<Integer> years(@Nonnull String eventId, @Nonnull OccurrenceReader calendar,
            @Nonnull String baseId, @Nonnull List<Finding> issues) {
        Integer first;
        Integer current;
        try {
            first = calendar.firstYear(eventId);
            current = calendar.currentYear(eventId);
        } catch (Throwable t) {
            issues.add(Finding.warning(AchievementPoolValidator.DOMAIN, "OCCURRENCE_CALENDAR_FAILED",
                    "the calendar failed to say which years '" + eventId + "' runs, so no copy exists until"
                            + " the next fold: " + t.getMessage(), baseId));
            return List.of();
        }
        if (first == null || current == null) {
            issues.add(Finding.warning(AchievementPoolValidator.DOMAIN, "OCCURRENCE_UNKNOWN_EVENT",
                    "Occurrence names the calendar event '" + eventId + "', which no loaded calendar event"
                            + " answers to with a first year, so no yearly copy exists; check the id, that the"
                            + " pack shipping the event is installed, and that its file states a FirstYear",
                    baseId));
            return List.of();
        }
        int last = current + 1;
        int from = first;
        if (last - from + 1 > MAX_YEARS) {
            from = last - MAX_YEARS + 1;
            issues.add(Finding.warning(AchievementPoolValidator.DOMAIN, "OCCURRENCE_SPAN_CLAMPED",
                    "'" + eventId + "' first occurs in " + first + ", so only its last " + MAX_YEARS
                            + " years get a copy; check its FirstYear", baseId));
        }
        List<Integer> out = new ArrayList<>();
        for (int year = from; year <= last; year++) {
            out.add(year);
        }
        return out;
    }

    /**
     * How an explicit child id reads in the {@code year} copy: the same year's copy when the child is
     * a non-skeleton file of the same event, else exactly as written.
     */
    @Nonnull
    static UnaryOperator<String> siblingRewrite(@Nonnull Map<String, AchievementAsset> assets,
            @Nonnull String eventId, int year) {
        return child -> {
            AchievementAsset sibling = assets.get(child);
            if (sibling == null || sibling.isAbstract() || sibling.getOccurrence() == null) {
                return child;
            }
            return eventId.equals(sibling.getOccurrence().eventIdOrNull()) ? mintedId(child, year) : child;
        };
    }

    /** The id one occurrence of {@code baseId} is minted under. */
    @Nonnull
    static String mintedId(@Nonnull String baseId, int year) {
        return baseId + "_" + year;
    }

    /** Is {@code value}, as a whole, the year sentinel (ignoring case and edge spaces)? */
    static boolean isYearSentinel(@Nullable String value) {
        return value != null && AchievementAsset.Occurrence.ARG_YEAR.equalsIgnoreCase(value.trim());
    }

    /** Is this occurrence live? A calendar that throws reads as not live, reported once per event. */
    static boolean live(@Nonnull OccurrenceReader calendar, @Nonnull String eventId, int year) {
        try {
            return calendar.isLive(eventId, year);
        } catch (Throwable t) {
            if (WARNED.add(eventId)) {
                SafeLog.warn("[achievement] the calendar could not say whether '" + eventId + "' is running,"
                        + " so its yearly achievements read as closed: " + t.getMessage());
            }
            return false;
        }
    }
}
