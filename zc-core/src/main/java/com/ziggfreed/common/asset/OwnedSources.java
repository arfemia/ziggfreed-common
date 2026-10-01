package com.ziggfreed.common.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * The code-registered, read-time sources a keyed config composes on top of its files, keyed by
 * OWNER id: the one mechanism behind every "a running mod adds to an entry while it is read" seam,
 * so each store that offers one gets the same rules instead of its own copy of them.
 *
 * <ul>
 *   <li><b>One source per owner.</b> The owner id matches without regard to case, and registering
 *       an owner again REPLACES its source, so a mod that re-runs its setup never doubles what it
 *       adds. {@link #unregister} removes it.</li>
 *   <li><b>Owner id order.</b> {@link #collect} asks the sources in owner id order, never in the
 *       order mods happened to set up in, so what a read answers is the same on every boot.</li>
 *   <li><b>A broken source costs only its own part.</b> A source that throws adds nothing to that
 *       read, and is warned once (until its owner registers again), so a read on a hot path never
 *       floods the log and never fails because one contributor did.</li>
 * </ul>
 *
 * <p>The store owning an instance decides what a source is asked and where its answer goes (after
 * which file content, into which leaf). A source is code, not content: a store keeps its authored
 * view free of it, so the source's owner validates what it adds.
 *
 * @param <S> the source type the owning store asks
 */
public final class OwnedSources<S> {

    /** Sources by lower-cased owner id, iterated in owner id order. */
    @Nonnull
    private final Map<String, S> sources = new ConcurrentSkipListMap<>();

    /** Owners whose source threw since they last registered, so a broken source warns once. */
    @Nonnull
    private final Set<String> warnedOwners = ConcurrentHashMap.newKeySet();

    /** Where a failing source is reported. */
    @Nonnull
    private volatile Consumer<String> warn = SafeLog::warn;

    /** Register {@code source} under {@code owner}, replacing any source that owner registered. */
    public void register(@Nonnull String owner, @Nonnull S source) {
        String key = owner.toLowerCase(Locale.ROOT);
        sources.put(key, Objects.requireNonNull(source, "source"));
        warnedOwners.remove(key);
    }

    /** Remove {@code owner}'s source, so it is asked no more. A no-op when it has none. */
    public void unregister(@Nonnull String owner) {
        String key = owner.toLowerCase(Locale.ROOT);
        sources.remove(key);
        warnedOwners.remove(key);
    }

    /** True when no owner has a source registered: the cheap check a read makes first. */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /**
     * Every source's answer to {@code ask}, concatenated in owner id order. A null answer and a null
     * element add nothing. A source that throws adds nothing either, and is reported once through
     * {@code describe} (handed the owner id, answering what failed, say
     * {@code "table 'x': the source of 'owner'"}), until its owner registers again.
     */
    @Nonnull
    public <T> List<T> collect(@Nonnull Function<? super S, ? extends Collection<? extends T>> ask,
            @Nonnull Function<String, String> describe) {
        List<T> out = new ArrayList<>();
        for (Map.Entry<String, S> entry : sources.entrySet()) {
            Collection<? extends T> answer;
            try {
                answer = ask.apply(entry.getValue());
            } catch (Throwable t) {
                if (warnedOwners.add(entry.getKey())) {
                    report(entry.getKey(), describe, t);
                }
                continue;
            }
            if (answer == null) {
                continue;
            }
            for (T element : answer) {
                if (element != null) {
                    out.add(element);
                }
            }
        }
        return out;
    }

    /** Route failing-source warnings to {@code sink} (null restores the log). A test seam. */
    public void warnInto(@Nullable Consumer<String> sink) {
        this.warn = sink != null ? sink : SafeLog::warn;
    }

    private void report(@Nonnull String owner, @Nonnull Function<String, String> describe,
            @Nonnull Throwable failure) {
        try {
            warn.accept(describe.apply(owner) + " failed and adds nothing until it registers again: "
                    + failure);
        } catch (Throwable ignored) {
            // A failing log line must never cost the read it was reporting on.
        }
    }
}
