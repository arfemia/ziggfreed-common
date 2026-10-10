package com.ziggfreed.common.playerstats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.counter.Counters;
import com.ziggfreed.common.playerstats.asset.StatAsset;
import com.ziggfreed.common.playerstats.asset.StatSourceAsset;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every stat the store knows, and every {@code From} entry filed by the objective kind it counts, so a
 * moment no stat names costs the counter one lookup and nothing else. Rebuilt whole whenever the stat
 * files load ({@code StatConfig}); a stat whose id the key format cannot hold, or whose {@code Kind} or
 * {@code Breakdown} nobody knows, is left out with one warning and never costs its neighbours (the
 * validator names it too).
 */
public final class StatIndex {

    /** One {@code From} entry the counter checks a moment against. */
    public record Line(@Nonnull String statId, @Nonnull StatKind kind, @Nonnull StatBreakdown breakdown,
                       @Nonnull ObjectiveDef def, boolean countsOne) {
    }

    /** No stat files: nothing is counted off the moment stream. */
    public static final StatIndex EMPTY = new StatIndex(Map.of(), Map.of());

    private final Map<String, List<Line>> byKind;
    private final Map<String, StatAsset> stats;

    private StatIndex(@Nonnull Map<String, List<Line>> byKind, @Nonnull Map<String, StatAsset> stats) {
        this.byKind = byKind;
        this.stats = stats;
    }

    /** The index over {@code stats}, keyed as the config holds them (lower-cased ids). */
    @Nonnull
    public static StatIndex of(@Nonnull Map<String, StatAsset> stats) {
        Map<String, List<Line>> byKind = new HashMap<>();
        Map<String, StatAsset> known = new HashMap<>();
        for (Map.Entry<String, StatAsset> entry : new TreeMap<>(stats).entrySet()) {
            StatAsset stat = entry.getValue();
            String statId = stat.getId() == null ? entry.getKey() : stat.getId();
            if (!StatKeys.usableStatId(statId)) {
                SafeLog.warn("[stats] the stat '" + statId + "' is skipped: its id is blank or carries / @ | or :");
                continue;
            }
            StatKind kind = stat.kind();
            StatBreakdown breakdown = stat.breakdown();
            if (kind == null || breakdown == null) {
                SafeLog.warn("[stats] the stat '" + statId + "' is skipped: its Kind or Breakdown is not a word"
                        + " the store knows");
                continue;
            }
            known.put(statId.trim().toLowerCase(Locale.ROOT), stat);
            if (!kind.counted()) {
                continue;
            }
            List<StatSourceAsset> sources = stat.from();
            for (int i = 0; i < sources.size(); i++) {
                StatSourceAsset source = sources.get(i);
                if (source.isBlank()) {
                    continue;
                }
                ObjectiveDef def = source.toDefBuilder(statId.trim() + "#" + i).build();
                byKind.computeIfAbsent(def.kind().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(new Line(StatKeys.total(statId), kind, breakdown, def, source.countsOne()));
            }
        }
        Map<String, List<Line>> frozen = new HashMap<>();
        for (Map.Entry<String, List<Line>> entry : byKind.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new StatIndex(Map.copyOf(frozen), Map.copyOf(known));
    }

    /** The lines counting {@code kindId}, matched without regard to case; empty for none. */
    @Nonnull
    public List<Line> forKind(@Nullable String kindId) {
        if (kindId == null) {
            return List.of();
        }
        List<Line> lines = byKind.get(kindId.toLowerCase(Locale.ROOT));
        return lines == null ? List.of() : lines;
    }

    /** The stat named {@code statId}, matched without regard to case, or null when no usable file defines it. */
    @Nullable
    public StatAsset stat(@Nullable String statId) {
        return statId == null ? null : stats.get(statId.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The stat a tally key belongs to: {@code Mob_Kills} and {@code Mob_Kills/Skeleton} both answer the
     * {@code Mob_Kills} file; null when no usable file defines it.
     */
    @Nullable
    public StatAsset statOfKey(@Nullable String key) {
        if (key == null) {
            return null;
        }
        int slash = key.indexOf(Counters.CATEGORY_SEPARATOR);
        return stat(slash < 0 ? key : key.substring(0, slash));
    }

    /** Every usable stat, keyed by lower-cased id. */
    @Nonnull
    public Map<String, StatAsset> stats() {
        return stats;
    }
}
