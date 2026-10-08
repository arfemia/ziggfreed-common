package com.ziggfreed.common.progress.gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a requirement opens: every module that keeps content behind a {@link GateSpec} (a shop offer, a board
 * line) registers a {@link Source} listing it, and a surface that explains a requirement asks back "what does
 * reaching this open?" without importing the module that owns the content. The Reputation page reads it to show
 * what each rank of a reputation opens; zc-commerce registers its shop offers at setup, so a server running the
 * library alone has the answer by default.
 *
 * <p>Sources are keyed by id (case-insensitive, last registration wins), asked live on every read so a reload
 * lands on the next one, and a source that throws costs only its own entries.
 */
public final class GatedContent {

    /**
     * One thing behind a requirement: the block that locks it, the item that pictures it (null for none),
     * {@code showsItem} when that picture IS the item the content hands over (so the item's own tooltip helps)
     * rather than a stand-in, what it is called, and where it is found (null when the name says enough).
     */
    public record Entry(@Nonnull GateSpec requires, @Nullable String iconItemId, boolean showsItem,
            @Nonnull Message name, @Nullable Message place) {

        public Entry {
            Objects.requireNonNull(requires, "requires");
            Objects.requireNonNull(name, "name");
            iconItemId = iconItemId == null || iconItemId.isBlank() ? null : iconItemId.trim();
        }
    }

    /** Lists the gated content one module owns, as it stands right now. */
    @FunctionalInterface
    public interface Source {

        @Nonnull
        List<Entry> entries();
    }

    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();

    private GatedContent() {
    }

    /** Register (or replace) the source under {@code id}. Setup time. */
    public static synchronized void register(@Nonnull String id, @Nonnull Source source) {
        SOURCES.put(key(id), Objects.requireNonNull(source, "source"));
    }

    /** Take the source under {@code id} back out; true when one was there. */
    public static synchronized boolean forget(@Nonnull String id) {
        return SOURCES.remove(key(id)) != null;
    }

    /** Every registered source's entries, in registration order. */
    @Nonnull
    public static List<Entry> all() {
        List<Source> sources;
        synchronized (GatedContent.class) {
            sources = List.copyOf(SOURCES.values());
        }
        List<Entry> out = new ArrayList<>();
        for (Source source : sources) {
            try {
                List<Entry> entries = source.entries();
                if (entries != null) {
                    for (Entry entry : entries) {
                        if (entry != null) {
                            out.add(entry);
                        }
                    }
                }
            } catch (Throwable t) {
                SafeLog.warn("[gate] a gated-content source could not list its entries", t);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Every factor bound a block asks to pass, from its own leaves, every {@code AllOf} group and every
     * {@code AnyOf} group (one route among several still opens the content); never a {@code Not} group, which
     * asks for the opposite.
     */
    @Nonnull
    public static List<FactorCondition> positiveFactors(@Nullable GateSpec spec) {
        if (spec == null) {
            return List.of();
        }
        List<FactorCondition> out = new ArrayList<>();
        add(out, spec);
        for (GateClause clause : spec.allOfOrEmpty()) {
            add(out, clause);
        }
        for (GateClause clause : spec.anyOfOrEmpty()) {
            add(out, clause);
        }
        return List.copyOf(out);
    }

    private static void add(@Nonnull List<FactorCondition> out, @Nullable GateClause clause) {
        if (clause == null) {
            return;
        }
        for (FactorCondition factor : clause.factorsOrEmpty()) {
            if (factor != null && !factor.isBlank()) {
                out.add(factor);
            }
        }
    }

    @Nonnull
    private static String key(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
