package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.ArrayList;
import java.util.Collection;
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
 * The stat board's sections, folded {@code defaults < pack < owner} like every keyed asset type, plus the
 * sections a store generates for what it registers in code ({@link #generated}, an owner at a time; a file
 * of the same id wins), and resolved once per change: the sections in tab order, and the set of lines any
 * of them names (with the stats one names as {@code Stat/*}, every line of which counts as named), which
 * is what decides whether a stat is ranked at all.
 */
public final class StatSectionConfig extends AbstractKeyedAssetConfig<StatSectionAsset> {

    private static final StatSectionConfig INSTANCE = new StatSectionConfig();

    private static final Comparator<StatSection> TAB_ORDER =
            Comparator.comparingInt(StatSection::order).thenComparing(StatSection::id);

    /** Each owner's generated sections, in the order owners first registered. Guarded by this. */
    private final Map<String, List<StatSection>> generated = new LinkedHashMap<>();

    private volatile List<StatSection> sections = List.of();
    /** The sections the files author, in tab order. */
    private volatile List<StatSection> authored = List.of();
    /** Every line some section names, lower-cased. */
    private volatile Set<String> named = Set.of();
    /** Every stat some section names as {@code Stat/*}, lower-cased: each of its lines is ranked, never its total. */
    private volatile Set<String> everyLine = Set.of();
    /** Section id -> the item its file pictures its tab by ({@code Icon}); a section naming none is absent. */
    private volatile Map<String, String> icons = Map.of();
    /** Section id -> its file's {@code HintKey}; a section naming none is absent. */
    private volatile Map<String, String> hintKeys = Map.of();

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

    /**
     * Put {@code owner}'s generated sections in place of what it generated before (none, or an empty list,
     * takes them out): a store's own sections for what it registers in code rather than in files. A section
     * file of the same id wins over a generated one, and the first owner to generate an id keeps it.
     */
    public synchronized void generated(@Nonnull String owner, @Nullable Collection<StatSection> owned) {
        if (owned == null || owned.isEmpty()) {
            generated.remove(owner);
        } else {
            generated.put(owner, List.copyOf(owned));
        }
        rebuild();
    }

    /** Every section, the generated ones included, in tab order. */
    @Nonnull
    public List<StatSection> sections() {
        return sections;
    }

    /** The sections the files author, without the generated ones, in tab order. */
    @Nonnull
    public List<StatSection> authored() {
        return authored;
    }

    /** The item section {@code sectionId}'s file pictures its tab by ({@code Icon}), or null when it names none. */
    @Nullable
    public String iconOf(@Nonnull String sectionId) {
        return icons.get(sectionId.toLowerCase(Locale.ROOT));
    }

    /**
     * The key of the line section {@code sectionId}'s file gives for how to get on its board ({@code HintKey}), as
     * written, or null when it names none.
     */
    @Nullable
    public String hintKeyOf(@Nonnull String sectionId) {
        return hintKeys.get(sectionId.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether some section names {@code line} (a stat id or {@code Stat/Thing}), matched without regard to
     * case: written as it is, or as one line of a stat a section names as {@code Stat/*}.
     */
    public boolean names(@Nullable String line) {
        if (line == null) {
            return false;
        }
        String folded = line.toLowerCase(Locale.ROOT);
        if (named.contains(folded)) {
            return true;
        }
        int slash = folded.indexOf('/');
        return slash > 0 && slash < folded.length() - 1 && everyLine.contains(folded.substring(0, slash));
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
        List<StatSection> files = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, String> pictures = new LinkedHashMap<>();
        Map<String, String> hints = new LinkedHashMap<>();
        for (Map.Entry<String, StatSectionAsset> e : all().entrySet()) {
            StatSection section = e.getValue().toSection(e.getKey());
            files.add(section);
            ids.add(section.id());
            if (e.getValue().icon() != null) {
                pictures.put(section.id(), e.getValue().icon());
            }
            if (e.getValue().hintKey() != null) {
                hints.put(section.id(), e.getValue().hintKey());
            }
        }
        icons = Map.copyOf(pictures);
        hintKeys = Map.copyOf(hints);
        List<StatSection> out = new ArrayList<>(files);
        for (List<StatSection> owned : generated.values()) {
            for (StatSection section : owned) {
                if (ids.add(section.id())) {
                    out.add(section);
                }
            }
        }
        Set<String> lines = new HashSet<>();
        Set<String> stats = new HashSet<>();
        for (StatSection section : out) {
            for (String line : section.lines()) {
                String stat = StatSection.everyLineOf(line);
                if (stat != null) {
                    stats.add(stat.toLowerCase(Locale.ROOT));
                } else {
                    lines.add(line.toLowerCase(Locale.ROOT));
                }
            }
        }
        files.sort(TAB_ORDER);
        out.sort(TAB_ORDER);
        authored = List.copyOf(files);
        sections = List.copyOf(out);
        named = Set.copyOf(lines);
        everyLine = Set.copyOf(stats);
    }
}
