package com.ziggfreed.common.almanac;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementAssetStore;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.BootAudit;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The one rule tying a season's keepsake to the cross-season ladder. An Almanac page's
 * {@code Keepsake} names its season's keepsake; every cross-season LADDER (an ordinary achievement
 * whose {@code MetaSelector} writes {@code AnyYear}) picks keepsakes by what they are filed under. When
 * the two disagree, a season silently never counts toward the ladder, or a yearly achievement counts
 * toward it without showing on any season's keepsake shelf. Both are WARNINGS: one pack may ship the
 * page and another the tag.
 *
 * <p>It reads the loaded files, never the folded pool or the calendar, so a season its owner switched
 * off is checked like any other and nothing depends on when the catalogue publishes. An unknown
 * keepsake (no file, or only an {@code Abstract} one, which never folds) is not this check's to report:
 * {@code AlmanacValidator}'s {@code UNKNOWN_KEEPSAKE} says it ({@link #loaded}). A ladder picks what the
 * fold would pick: what {@link AchievementAsset#matchedBy} matches, never a capstone (one listing
 * {@code MetaChildren} or writing a {@code MetaSelector}) and never an {@code Abstract} file, so a keepsake
 * that is a capstone never counts. A picked achievement no page names is reported whether it is yearly or
 * ordinary: the fold counts an ordinary one as a season of its own, on no keepsake shelf.
 *
 * <p>A boot prints its findings once: the {@link #logFindings() build hook} prints them, unless the
 * process asked for zc's boot audit ({@code BootAudit.ENV}), whose Almanac pass
 * ({@code AlmanacValidator.audit()}) carries them instead and counts them with the rest. While the owner
 * has the Almanac switched off, off means absent and neither says anything.
 */
public final class AlmanacKeepsakeCheck {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "almanac";

    /** The label the build hook's lines carry. */
    public static final String LOG_LABEL = "[almanac] season keepsakes";

    /** A season's keepsake the ladder does not pick. */
    public static final String KEEPSAKE_NOT_PICKED = "KEEPSAKE_NOT_PICKED";

    /** An achievement the ladder picks, yearly or ordinary, that no season names as its keepsake. */
    public static final String PICKED_NOT_A_KEEPSAKE = "PICKED_NOT_A_KEEPSAKE";

    private AlmanacKeepsakeCheck() {
    }

    /**
     * The build hook: check the loaded pages against the loaded achievement files, and log what
     * disagrees, unless zc's boot audit was asked for and reports it in its Almanac pass.
     */
    public static void logFindings() {
        logFindings(BootAudit.asked(), SafeLog::warn, SafeLog::info);
    }

    /** {@link #logFindings()} with the boot audit's switch and the sinks in hand. */
    static void logFindings(boolean bootAuditAsked, @Nonnull Consumer<String> errorSink,
            @Nonnull Consumer<String> noteSink) {
        if (bootAuditAsked) {
            return;
        }
        ValidationReport.logAll(LOG_LABEL, findings(), errorSink, noteSink);
    }

    /**
     * The engine walk: the loaded pages against the loaded achievement files. Empty while the owner has
     * the Almanac switched off; it never throws.
     */
    @Nonnull
    public static List<Finding> findings() {
        try {
            if (!AlmanacSwitch.isOn()) {
                return List.of();
            }
            return findings(AlmanacEntryConfig.getInstance().all(), AchievementAssetStore.getInstance().assets());
        } catch (Throwable t) {
            SafeLog.warn("[almanac] the keepsake check failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * Where {@code pages} (keyed by event id) and the ladders among {@code achievements} (keyed by
     * folded id) disagree, sorted by source; empty when there is no ladder at all.
     */
    @Nonnull
    public static List<Finding> findings(@Nonnull Map<String, AlmanacEntryAsset> pages,
            @Nonnull Map<String, AchievementAsset> achievements) {
        Map<String, AchievementAsset> files = new TreeMap<>(achievements);
        Map<String, AchievementAsset.MetaSelector> ladders = new TreeMap<>();
        for (Map.Entry<String, AchievementAsset> file : files.entrySet()) {
            AchievementAsset asset = file.getValue();
            AchievementAsset.MetaSelector selector = asset == null ? null : asset.getMetaSelector();
            if (selector != null && selector.isAnyYear() && !selector.isEmpty() && !asset.isAbstract()
                    && asset.getOccurrence() == null) {
                ladders.put(file.getKey(), selector);
            }
        }
        List<Finding> out = new ArrayList<>();
        if (ladders.isEmpty()) {
            return out;
        }

        Set<String> keepsakes = new TreeSet<>();
        for (Map.Entry<String, AlmanacEntryAsset> page : new TreeMap<>(pages).entrySet()) {
            String named = page.getValue() == null ? null : page.getValue().getKeepsake();
            if (named == null) {
                continue;
            }
            String keepsake = AlmanacKeys.normalize(named);
            keepsakes.add(keepsake);
            AchievementAsset asset = files.get(keepsake);
            if (asset == null || asset.isAbstract()) {
                // An unknown keepsake, an Abstract base included: the Almanac audit's UNKNOWN_KEEPSAKE says it.
                continue;
            }
            for (Map.Entry<String, AchievementAsset.MetaSelector> ladder : ladders.entrySet()) {
                if (!picks(ladder.getValue(), asset)) {
                    out.add(Finding.warning(DOMAIN, KEEPSAKE_NOT_PICKED,
                            "the season names '" + keepsake + "' as its Keepsake, but the cross-season achievement '"
                                    + ladder.getKey() + "' does not pick it" + whyNotPicked(asset), page.getKey()));
                }
            }
        }

        for (Map.Entry<String, AchievementAsset> file : files.entrySet()) {
            AchievementAsset asset = file.getValue();
            if (asset == null || asset.isAbstract() || named(file.getKey(), keepsakes)) {
                continue;
            }
            for (Map.Entry<String, AchievementAsset.MetaSelector> ladder : ladders.entrySet()) {
                if (picks(ladder.getValue(), asset)) {
                    String message = asset.getOccurrence() != null
                            ? pickedYearly(ladder.getKey()) : pickedOrdinary(ladder.getKey());
                    out.add(Finding.warning(DOMAIN, PICKED_NOT_A_KEEPSAKE, message, file.getKey()));
                }
            }
        }
        return out;
    }

    /** A {@link #PICKED_NOT_A_KEEPSAKE} message for a yearly achievement. */
    @Nonnull
    private static String pickedYearly(@Nonnull String ladder) {
        return "this yearly achievement is filed so the cross-season achievement '" + ladder + "' counts it, but no "
                + "Almanac page names it as its season's Keepsake, so it counts toward the ladder without showing on "
                + "any keepsake shelf; name it in its season's page, or take the tag off";
    }

    /** A {@link #PICKED_NOT_A_KEEPSAKE} message for an ordinary achievement, which comes back with no event. */
    @Nonnull
    private static String pickedOrdinary(@Nonnull String ladder) {
        return "this achievement is filed so the cross-season achievement '" + ladder + "' counts it, but it comes "
                + "back with no calendar event, so it counts as a season of its own, always in the count and on no "
                + "keepsake shelf; take the tag off, or give it its season's Occurrence and name it in that season's "
                + "page";
    }

    /**
     * Does {@code keepsake} (normalized) name an achievement among {@code files} (keyed by folded id), as the
     * Almanac finds one: a file that folds (not {@code Abstract}) whose id is the keepsake's, or one year's copy of
     * it ({@code <keepsake>_<yyyy>}, the id the Almanac falls back to for a copy carrying no occurrence)?
     */
    public static boolean loaded(@Nonnull String keepsake, @Nonnull Map<String, AchievementAsset> files) {
        for (Map.Entry<String, AchievementAsset> file : files.entrySet()) {
            AchievementAsset asset = file.getValue();
            if (asset != null && !asset.isAbstract() && standsFor(AlmanacKeys.normalize(file.getKey()), keepsake)) {
                return true;
            }
        }
        return false;
    }

    /** Is {@code fileId} named by one of {@code keepsakes}: its own id, or one year's copy of it? */
    private static boolean named(@Nonnull String fileId, @Nonnull Set<String> keepsakes) {
        for (String keepsake : keepsakes) {
            if (standsFor(fileId, keepsake)) {
                return true;
            }
        }
        return false;
    }

    /** Is {@code fileId} {@code keepsake} itself, or {@code <keepsake>_<yyyy>} (a year from 1)? */
    private static boolean standsFor(@Nonnull String fileId, @Nonnull String keepsake) {
        if (fileId.equals(keepsake)) {
            return true;
        }
        String prefix = keepsake + "_";
        if (!fileId.startsWith(prefix) || fileId.length() != prefix.length() + 4) {
            return false;
        }
        int year = 0;
        for (int i = prefix.length(); i < fileId.length(); i++) {
            char c = fileId.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            year = year * 10 + (c - '0');
        }
        return year > 0;
    }

    /** Would the fold let {@code ladder} pick {@code asset}: a file that folds, filed to match, and no capstone? */
    private static boolean picks(@Nonnull AchievementAsset.MetaSelector ladder, @Nonnull AchievementAsset asset) {
        return !asset.isAbstract() && !isCapstone(asset) && asset.matchedBy(ladder);
    }

    /** The rest of a {@link #KEEPSAKE_NOT_PICKED} message: why, and what to do about it. */
    @Nonnull
    private static String whyNotPicked(@Nonnull AchievementAsset keepsake) {
        if (isCapstone(keepsake)) {
            return ": it is a capstone (it lists MetaChildren or writes a MetaSelector), and a selector never "
                    + "picks a capstone, so this season never counts toward it; make the keepsake an ordinary "
                    + "achievement";
        }
        return " (its MetaSelector's Category, Subcategory and Tags), so this season never counts toward it; add "
                + "the tag to the keepsake's Listing";
    }

    /** A capstone, which no selector ever picks: one listing a MetaChildren id, or writing a MetaSelector. */
    private static boolean isCapstone(@Nonnull AchievementAsset asset) {
        if (asset.getMetaSelector() != null) {
            return true;
        }
        for (String child : asset.metaChildrenOrEmpty()) {
            if (child != null && !child.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
