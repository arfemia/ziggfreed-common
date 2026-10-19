package com.ziggfreed.common.usegate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.inventory.ItemLineage;
import com.ziggfreed.common.match.NameMatchRank;
import com.ziggfreed.common.match.NamePattern;

/**
 * The folded use gates turned around for the moment's question "which gates is this about?": per axis, every
 * authored pattern with its gate, and per looked-up id, the matching gates ordered most specific first,
 * memoized (an id's answer is built once and kept until the content changes). Pure over the gates and a lineage
 * lookup; rebuilt whole when the content changes.
 *
 * <p>Only switched-on files are indexed as candidates; whether a gate is PRESENT right now (its switches) is asked
 * at the moment by {@link UseGates}, since a feature can move while the server runs. The switched-off gates of the
 * {@value ItemLevelTags#GROUP} group are kept apart, for the one question they answer: does an owner switch an
 * item's {@code ZigLevel} tag off ({@link #switchesOffTag})?
 */
public final class UseGateIndex {

    /** No gates at all: every question answers nothing. */
    public static final UseGateIndex EMPTY = new UseGateIndex(List.of(), ItemLineage::of);

    /**
     * One gate matching one id: the gate, how far up the id's lineage the match was found (0 is the id itself)
     * and how closely its best pattern named it.
     */
    public record Candidate(@Nonnull UseGateAsset gate, int depth, @Nonnull NameMatchRank rank) {
    }

    private static final Comparator<Candidate> MOST_SPECIFIC_FIRST = Comparator
            .comparingInt(Candidate::depth)
            .thenComparing(Candidate::rank)
            .thenComparing(c -> c.gate().getId(), String.CASE_INSENSITIVE_ORDER);

    private record Entry(@Nonnull NamePattern pattern, @Nonnull UseGateAsset gate) {
    }

    private final Map<UseGateAxis, List<Entry>> byAxis = new EnumMap<>(UseGateAxis.class);
    private final Map<UseGateAxis, Map<String, List<Candidate>>> memo = new EnumMap<>(UseGateAxis.class);
    /** The switched-off gates of the ZigLevel group, per item axis. */
    private final Map<UseGateAxis, List<Entry>> tagOffByAxis = new EnumMap<>(UseGateAxis.class);
    private final Map<UseGateAxis, Map<String, Boolean>> tagOffMemo = new EnumMap<>(UseGateAxis.class);
    private final Function<String, List<String>> lineageOf;
    private final boolean empty;

    private UseGateIndex(@Nonnull Collection<UseGateAsset> gates, @Nonnull Function<String, List<String>> lineageOf) {
        this.lineageOf = lineageOf;
        boolean any = false;
        for (UseGateAxis axis : UseGateAxis.values()) {
            List<Entry> entries = new ArrayList<>();
            List<Entry> tagOff = new ArrayList<>();
            for (UseGateAsset gate : gates) {
                if (gate == null || gate.getId() == null) {
                    continue;
                }
                List<Entry> into = gate.isEnabled() ? entries
                        : axis.readsItem() && ItemLevelTags.GROUP_KEY.equals(gate.group()) ? tagOff : null;
                if (into == null) {
                    continue;
                }
                for (NamePattern pattern : gate.on().patterns(axis)) {
                    into.add(new Entry(pattern, gate));
                }
            }
            any |= !entries.isEmpty();
            byAxis.put(axis, List.copyOf(entries));
            memo.put(axis, new ConcurrentHashMap<>());
            tagOffByAxis.put(axis, List.copyOf(tagOff));
            tagOffMemo.put(axis, new ConcurrentHashMap<>());
        }
        this.empty = !any;
    }

    /** The index over {@code gates}, matching item and block ids along their live {@code Parent} lineage. */
    @Nonnull
    public static UseGateIndex of(@Nonnull Collection<UseGateAsset> gates) {
        return new UseGateIndex(gates, ItemLineage::of);
    }

    /** The index over {@code gates} with a lineage lookup of the caller's own (a test's). */
    @Nonnull
    public static UseGateIndex of(@Nonnull Collection<UseGateAsset> gates,
            @Nonnull Function<String, List<String>> lineageOf) {
        return new UseGateIndex(gates, lineageOf);
    }

    /** True when no switched-on gate authors any pattern: the one read a moment pays when nothing is authored. */
    public boolean isEmpty() {
        return empty;
    }

    /** True when no switched-on gate authors a pattern on {@code axis}. */
    public boolean isEmpty(@Nonnull UseGateAxis axis) {
        return byAxis.get(axis).isEmpty();
    }

    /**
     * The gates whose {@code axis} patterns match {@code id}, most specific first: the id's own match before an
     * ancestor's (item and block ids walk their lineage), then the closer pattern, then the gate id. Empty for a
     * null or blank id. A gate appears once, at its best match.
     */
    @Nonnull
    public List<Candidate> candidates(@Nonnull UseGateAxis axis, @Nullable String id) {
        if (id == null || id.isBlank() || isEmpty(axis)) {
            return List.of();
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        return memo.get(axis).computeIfAbsent(key, k -> build(axis, id.trim()));
    }

    /**
     * Does a switched-off gate of the {@value ItemLevelTags#GROUP} group name {@code id} on {@code axis} (an item
     * axis), matched along the item's lineage as every item pattern is? Then that item's {@code ZigLevel} tag asks
     * nothing on that axis.
     */
    public boolean switchesOffTag(@Nonnull UseGateAxis axis, @Nullable String id) {
        if (id == null || id.isBlank() || !axis.readsItem() || tagOffByAxis.get(axis).isEmpty()) {
            return false;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        return tagOffMemo.get(axis).computeIfAbsent(key, k -> namesAny(tagOffByAxis.get(axis), axis, id.trim()));
    }

    private boolean namesAny(@Nonnull List<Entry> entries, @Nonnull UseGateAxis axis, @Nonnull String id) {
        List<String> lineage = axis.walksLineage() ? lineageOf.apply(id) : List.of(id);
        for (String candidate : lineage) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            for (Entry entry : entries) {
                if (entry.pattern().matches(lower)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nonnull
    private List<Candidate> build(@Nonnull UseGateAxis axis, @Nonnull String id) {
        List<String> lineage = axis.walksLineage() ? lineageOf.apply(id) : List.of(id);
        Map<UseGateAsset, Candidate> best = new IdentityHashMap<>();
        for (int depth = 0; depth < lineage.size(); depth++) {
            String candidate = lineage.get(depth);
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            for (Entry entry : byAxis.get(axis)) {
                if (!entry.pattern().matches(lower)) {
                    continue;
                }
                Candidate found = new Candidate(entry.gate(), depth, NameMatchRank.ofPattern(entry.pattern()));
                Candidate held = best.get(entry.gate());
                if (held == null || MOST_SPECIFIC_FIRST.compare(found, held) < 0) {
                    best.put(entry.gate(), found);
                }
            }
        }
        if (best.isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>(best.values());
        out.sort(MOST_SPECIFIC_FIRST);
        return Collections.unmodifiableList(out);
    }
}
