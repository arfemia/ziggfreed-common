package com.ziggfreed.common.ui.kit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

/**
 * The one seam through which a module adds sections to another module's ledger list without a module edge: the
 * book's Statistics view lists every {@link LedgerSource} contributed to {@link #STATISTICS} (the MMO's statistics,
 * zc-almanac's seasons), and opens a row's page from the source that owns it. A source contributes at setup time;
 * the same id contributed again replaces the first (a reload), and {@link #sources} reads them by
 * {@link LedgerSource#order()}, then id.
 */
public final class LedgerContributions {

    /** The book's Statistics view. */
    public static final String STATISTICS = "ziggfreedcommon:statistics";

    private static final Map<String, Map<String, LedgerSource>> SURFACES = new ConcurrentHashMap<>();

    private static final Comparator<LedgerSource> ORDER =
            Comparator.comparingInt(LedgerSource::order).thenComparing(LedgerSource::id);

    private LedgerContributions() {
    }

    /** Add {@code source} to {@code surface}, replacing any source with the same id. */
    public static void contribute(@Nonnull String surface, @Nonnull LedgerSource source) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(source, "source");
        SURFACES.computeIfAbsent(surface, s -> new ConcurrentHashMap<>()).put(source.id(), source);
    }

    /** The sources contributed to {@code surface}, by order then id; empty when none. */
    @Nonnull
    public static List<LedgerSource> sources(@Nonnull String surface) {
        Map<String, LedgerSource> sources = SURFACES.get(surface);
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<LedgerSource> sorted = new ArrayList<>(sources.values());
        sorted.sort(ORDER);
        return List.copyOf(sorted);
    }

    /** Forget every contribution. */
    public static void resetForTests() {
        SURFACES.clear();
    }
}
