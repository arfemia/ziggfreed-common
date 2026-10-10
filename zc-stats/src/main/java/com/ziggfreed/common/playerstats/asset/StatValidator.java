package com.ziggfreed.common.playerstats.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.counter.Counters;
import com.ziggfreed.common.playerstats.StatKeys;
import com.ziggfreed.common.playerstats.StatKind;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * The content audit over the folded stat files and the stat lines the board's sections name, filed under
 * the {@value #DOMAIN} domain: what an author got wrong, said while the file is still open.
 *
 * <p>Split the way every validator in this family is: {@link #audit()} is the engine walk (it never
 * throws) and the second {@code audit} is the pure core a test drives. The section lines come through
 * {@link #sections}, filled by whoever owns the sections; an unfilled source checks none.
 *
 * <p>The codes, each a stable machine token a consumer may filter on:
 * <ul>
 *   <li>ERROR {@link #STAT_ID_UNUSABLE} (the id carries {@code / @ | :}, so no tally can be kept under it),
 *       {@link #UNKNOWN_KIND}, {@link #UNKNOWN_FORMAT}, {@link #UNKNOWN_BREAKDOWN} (a word the store does
 *       not know, so the stat is left out);</li>
 *   <li>WARNING {@link #SOURCE_WITHOUT_KIND} (a {@code From} entry that can never count),
 *       {@link #UNKNOWN_COUNT} (read as Moment), {@link #UNKNOWN_SECTION_STAT} (a section names a stat no
 *       file defines: its owner may register later, or be a mod this server does not run), and
 *       {@link TextKeyAudit#UNKNOWN_TEXT_KEY} for a {@code NameKey} no loaded lang file ships;</li>
 *   <li>INFO {@link #SOURCES_IGNORED} (a Gauge or Native stat lists {@code From} entries nothing reads),
 *       {@link #UNNAMED_STAT} (no {@code NameKey}, so it reads as its id).</li>
 * </ul>
 */
public final class StatValidator {

    public static final String DOMAIN = "stats";

    public static final String STAT_ID_UNUSABLE = "STAT_ID_UNUSABLE";
    public static final String UNKNOWN_KIND = "UNKNOWN_STAT_KIND";
    public static final String UNKNOWN_FORMAT = "UNKNOWN_STAT_FORMAT";
    public static final String UNKNOWN_BREAKDOWN = "UNKNOWN_STAT_BREAKDOWN";
    public static final String SOURCE_WITHOUT_KIND = "STAT_SOURCE_WITHOUT_KIND";
    public static final String UNKNOWN_COUNT = "UNKNOWN_STAT_COUNT";
    public static final String UNKNOWN_SECTION_STAT = "UNKNOWN_SECTION_STAT";
    public static final String SOURCES_IGNORED = "STAT_SOURCES_IGNORED";
    public static final String UNNAMED_STAT = "UNNAMED_STAT";

    private static volatile Supplier<Map<String, ? extends Collection<String>>> sections = Map::of;

    private StatValidator() {
    }

    /**
     * Where the section lines come from: section id to the stat lines it names ({@code Mob_Kills} or
     * {@code Mob_Kills/Skeleton}). One call, from whoever registers the sections; null checks none.
     */
    public static void sections(@Nullable Supplier<Map<String, ? extends Collection<String>>> source) {
        sections = source == null ? Map::of : source;
    }

    /** The engine walk over every folded stat and the sections' lines. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            return audit(StatConfig.getInstance().all().values(), sections.get(), TextKeyAudit.liveCatalogue());
        } catch (Throwable t) {
            SafeLog.warn("[stats] the stat audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core: the stats in id order, then each section's lines in section order.
     *
     * @param stats        every folded stat file
     * @param sectionLines section id to the stat lines it names
     * @param keyShipped   whether a loaded lang file ships a key
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<StatAsset> stats,
            @Nonnull Map<String, ? extends Collection<String>> sectionLines, @Nonnull Predicate<String> keyShipped) {
        List<Finding> out = new ArrayList<>();
        List<StatAsset> ordered = new ArrayList<>();
        Set<String> defined = new HashSet<>();
        for (StatAsset stat : stats) {
            if (stat != null && stat.getId() != null) {
                ordered.add(stat);
                defined.add(stat.getId().trim().toLowerCase(Locale.ROOT));
            }
        }
        ordered.sort(Comparator.comparing(StatAsset::getId));
        for (StatAsset stat : ordered) {
            auditStat(stat, keyShipped, out);
        }
        for (Map.Entry<String, ? extends Collection<String>> section : new TreeMap<>(sectionLines).entrySet()) {
            if (section.getValue() == null) {
                continue;
            }
            for (String line : section.getValue()) {
                String statId = statOf(line);
                if (statId != null && !defined.contains(statId.toLowerCase(Locale.ROOT))) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_SECTION_STAT, "the section '" + section.getKey()
                            + "' names the stat line '" + line + "', but no stat file defines '" + statId
                            + "', so its column stays empty until a mod that ships it loads", section.getKey()));
                }
            }
        }
        return out;
    }

    private static void auditStat(@Nonnull StatAsset stat, @Nonnull Predicate<String> keyShipped,
            @Nonnull List<Finding> out) {
        String id = stat.getId();
        String where = "the stat '" + id + "'";
        if (!StatKeys.usableStatId(id)) {
            out.add(Finding.error(DOMAIN, STAT_ID_UNUSABLE, where + " has an id carrying / @ | or :, which a "
                    + "player's tallies reserve, so nothing can be counted under it; rename its file", id));
        }
        StatKind kind = stat.kind();
        if (kind == null) {
            out.add(Finding.error(DOMAIN, UNKNOWN_KIND, where + " names the Kind '" + stat.getKind()
                    + "'; write Sum, Max, Min, Gauge or Native. The stat is left out", id));
        }
        if (stat.format() == null) {
            out.add(Finding.error(DOMAIN, UNKNOWN_FORMAT, where + " names the Format '" + stat.getFormat()
                    + "'; write Count, Time, Distance or Percent. The stat still counts, but the board gives it no"
                    + " column", id));
        }
        if (stat.breakdown() == null) {
            out.add(Finding.error(DOMAIN, UNKNOWN_BREAKDOWN, where + " names the Breakdown '"
                    + stat.getBreakdown() + "'; write None, Target, Qualifier or TargetAtQualifier. The stat is"
                    + " left out", id));
        }
        List<StatSourceAsset> sources = stat.from();
        if (kind != null && !kind.counted() && !sources.isEmpty()) {
            out.add(Finding.info(DOMAIN, SOURCES_IGNORED, where + " is a " + kind.authored() + " stat, which is "
                    + "never counted off moments, so its From entries are not read", id));
        }
        for (int i = 0; i < sources.size(); i++) {
            StatSourceAsset source = sources.get(i);
            if (source.isBlank()) {
                out.add(Finding.warning(DOMAIN, SOURCE_WITHOUT_KIND, where + " From entry " + (i + 1)
                        + " names no Kind, so it can never count", id));
            }
            if (!source.countKnown()) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_COUNT, where + " From entry " + (i + 1) + " names the Count '"
                        + source.getCount() + "'; write Moment or One. It counts as Moment", id));
            }
        }
        TextKeyAudit.check(out, DOMAIN, id, where + " NameKey", stat.nameKey(), keyShipped,
                "it reads as its id spelled out");
        if (stat.nameKey() == null) {
            out.add(Finding.info(DOMAIN, UNNAMED_STAT, where + " has no NameKey, so players read it as its id", id));
        }
    }

    /** The stat id a section line names: the line itself, or the part before its first {@code /}. */
    @Nullable
    private static String statOf(@Nullable String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String trimmed = line.trim();
        int slash = trimmed.indexOf(Counters.CATEGORY_SEPARATOR);
        return slash < 0 ? trimmed : slash == 0 ? null : trimmed.substring(0, slash);
    }
}
