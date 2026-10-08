package com.ziggfreed.common.almanac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacStatAsset;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every stat line of every season page, filed by the objective kind it counts, so a moment no page
 * names costs the counter one lookup and nothing else, and every item a page's collection lists, for the
 * owned marks ({@link AlmanacCollection}), so an item no page lists costs one lookup too. Rebuilt whole
 * whenever a page layer changes ({@code AlmanacEntryConfig}); a line or page whose name the record format
 * reserves, or a line with no {@code Kind}, is skipped with one warning and never costs its neighbours.
 */
public final class AlmanacIndex {

    /** One line the counter checks a moment against. */
    public record Line(@Nonnull String eventId, @Nonnull String statId, @Nonnull ObjectiveDef def,
                       boolean liveOnly) {
    }

    /** No pages: nothing is counted but attendance, and no item is tracked. */
    public static final AlmanacIndex EMPTY = new AlmanacIndex(Map.of(), Set.of());

    private final Map<String, List<Line>> byKind;

    /** Every item id a usable page's collections list, hidden or not, lower-cased. */
    private final Set<String> tracked;

    private AlmanacIndex(@Nonnull Map<String, List<Line>> byKind, @Nonnull Set<String> tracked) {
        this.byKind = byKind;
        this.tracked = tracked;
    }

    /** The index over {@code pages}, keyed by event id as the config holds them. */
    @Nonnull
    public static AlmanacIndex of(@Nonnull Map<String, AlmanacEntryAsset> pages) {
        Map<String, List<Line>> byKind = new HashMap<>();
        Set<String> tracked = new HashSet<>();
        for (Map.Entry<String, AlmanacEntryAsset> page : new TreeMap<>(pages).entrySet()) {
            String eventId = AlmanacKeys.normalize(page.getKey());
            if (!AlmanacKeys.usableId(eventId)) {
                SafeLog.warn("[almanac] the page '" + page.getKey() + "' is skipped: its name uses a character "
                        + "the tally format reserves (/ @ | : or a leading $)");
                continue;
            }
            for (String itemId : AlmanacCollection.itemsOf(page.getValue())) {
                tracked.add(AlmanacKeys.normalize(itemId));
            }
            for (Map.Entry<String, AlmanacStatAsset> stat : new TreeMap<>(page.getValue().getStats()).entrySet()) {
                String statId = AlmanacKeys.normalize(stat.getKey());
                if (!AlmanacKeys.usableId(statId)) {
                    SafeLog.warn("[almanac] the line '" + stat.getKey() + "' of page '" + eventId + "' is skipped: "
                            + "its name uses a character the tally format reserves (/ @ | : or a leading $)");
                    continue;
                }
                if (stat.getValue().isBlank()) {
                    SafeLog.warn("[almanac] the line '" + statId + "' of page '" + eventId + "' names no Kind, "
                            + "so nothing could ever count it");
                    continue;
                }
                ObjectiveDef def = stat.getValue().toDefBuilder(statId).build();
                byKind.computeIfAbsent(def.kind().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(new Line(eventId, statId, def, stat.getValue().isLiveOnly()));
            }
        }
        Map<String, List<Line>> frozen = new HashMap<>();
        for (Map.Entry<String, List<Line>> entry : byKind.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new AlmanacIndex(Map.copyOf(frozen), Set.copyOf(tracked));
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

    /** True when no page names any line. */
    public boolean isEmpty() {
        return byKind.isEmpty();
    }

    /** Does some page's collection list {@code itemId}? Matched without regard to case; false for no id. */
    public boolean tracks(@Nullable String itemId) {
        return itemId != null && !itemId.isBlank() && tracked.contains(AlmanacKeys.normalize(itemId));
    }

    /** Every item id the collections list, lower-cased; unmodifiable, empty when none does. */
    @Nonnull
    public Set<String> trackedItems() {
        return tracked;
    }
}
