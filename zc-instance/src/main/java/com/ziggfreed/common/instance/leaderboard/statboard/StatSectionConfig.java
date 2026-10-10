package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The stat board's sections, folded {@code defaults < pack < owner} like every keyed asset type, and
 * resolved once per fold: the sections in tab order, and the set of lines any of them names, which is
 * what decides whether a stat is ranked at all.
 */
public final class StatSectionConfig extends AbstractKeyedAssetConfig<StatSectionAsset> {

    private static final StatSectionConfig INSTANCE = new StatSectionConfig();

    private static final Comparator<StatSection> TAB_ORDER =
            Comparator.comparingInt(StatSection::order).thenComparing(StatSection::id);

    private volatile List<StatSection> sections = List.of();
    /** Every line some section names, lower-cased. */
    private volatile Set<String> named = Set.of();

    @Nonnull
    public static StatSectionConfig getInstance() {
        return INSTANCE;
    }

    private StatSectionConfig() {
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, StatSectionAsset> layer) {
        super.mergePackLayer(layer);
        rebuild();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, StatSectionAsset> layer) {
        super.mergeOwnerLayer(layer);
        rebuild();
    }

    /** Every section, in tab order. */
    @Nonnull
    public List<StatSection> sections() {
        return sections;
    }

    /** Whether some section names {@code line} (a stat id or {@code Stat/Thing}), matched without regard to case. */
    public boolean names(@Nullable String line) {
        return line != null && named.contains(line.toLowerCase(Locale.ROOT));
    }

    /** The entries of {@code values} some section names, in their order; empty when none is. */
    @Nonnull
    public Map<String, Long> ranked(@Nullable Map<String, Long> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : values.entrySet()) {
            if (e.getValue() != null && names(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private void rebuild() {
        List<StatSection> out = new ArrayList<>();
        Set<String> lines = new HashSet<>();
        for (Map.Entry<String, StatSectionAsset> e : all().entrySet()) {
            StatSection section = e.getValue().toSection(e.getKey());
            out.add(section);
            for (String line : section.lines()) {
                lines.add(line.toLowerCase(Locale.ROOT));
            }
        }
        out.sort(TAB_ORDER);
        sections = List.copyOf(out);
        named = Set.copyOf(lines);
    }
}
