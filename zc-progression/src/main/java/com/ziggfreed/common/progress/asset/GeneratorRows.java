package com.ziggfreed.common.progress.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonPrimitive;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's own axis source, {@value #SOURCE}: the switched-on {@link GeneratorRowAsset}s of one set, in their
 * {@code Order}, each row binding its {@code Values}. Any generator in any store reads it with
 *
 * <pre>{@code
 * { "Source": "ziggfreedcommon:rows", "Filter": { "Set": "Yours_Schools" } }
 * }</pre>
 *
 * <p>{@link GeneratorCore} answers it itself, before any source a store was handed, so every store that walks axes
 * has it on a server running the library alone and none of them registers it (M513).
 *
 * <p><b>The re-fold.</b> A generator's family is written when its store folds, so a row added, retuned or switched
 * off afterwards (a pack re-imported, an owner's edit of {@code generator-rows.json}) has to write the family again.
 * The core notes which sets each generator type walked ({@link #walked}); a store registers how to re-expand its
 * generators once, by generator type ({@link #onRefold}); and {@link #settle}, run after every fold of the rows,
 * re-expands each store that walked a set whose rows moved since the last settle, and no other.
 */
public final class GeneratorRows {

    /** The source id a generator's axis names to walk a set of rows. */
    public static final String SOURCE = "ziggfreedcommon:rows";

    /** The {@code Filter} key naming the set an axis walks. */
    public static final String FILTER_SET = "Set";

    /** Each generator type to the sets (lower-cased) its generators walked. */
    private static final Map<Class<?>, Set<String>> WALKED = new ConcurrentHashMap<>();

    /** Each generator type to how its store expands its generators again. */
    private static final Map<Class<?>, Runnable> REFOLDS = new ConcurrentHashMap<>();

    private static final Object SETTLE_LOCK = new Object();

    /** Each set (lower-cased) to its rows' bindings at the last settle. Guarded by {@link #SETTLE_LOCK}. */
    @Nonnull
    private static Map<String, List<Map<String, JsonPrimitive>>> settled = Map.of();

    private GeneratorRows() {
    }

    /** Does {@code sourceId} name this source, without regard to case? */
    public static boolean isSource(@Nullable String sourceId) {
        return sourceId != null && SOURCE.equalsIgnoreCase(sourceId.trim());
    }

    /** The set an axis's {@code Filter} names ({@value #FILTER_SET}, the key without regard to case), or null. */
    @Nullable
    public static String setOf(@Nonnull Map<String, String> filter) {
        for (Map.Entry<String, String> entry : filter.entrySet()) {
            if (entry.getKey() != null && FILTER_SET.equalsIgnoreCase(entry.getKey().trim())
                    && entry.getValue() != null && !entry.getValue().isBlank()) {
                return entry.getValue().trim();
            }
        }
        return null;
    }

    /**
     * Every switched-on row of {@code set} (matched without regard to case), lowest {@code Order} first and the row
     * id breaking a tie, each as the tokens it binds; a row binding nothing is left out.
     */
    @Nonnull
    public static List<Map<String, JsonPrimitive>> rows(@Nonnull String set) {
        List<GeneratorRowAsset> members = new ArrayList<>();
        for (GeneratorRowAsset row : GeneratorRowConfig.getInstance().all().values()) {
            if (row != null && row.isEnabled() && row.inSet(set)) {
                members.add(row);
            }
        }
        members.sort(Comparator.comparingInt(GeneratorRowAsset::order).thenComparing(GeneratorRowAsset::key));
        List<Map<String, JsonPrimitive>> out = new ArrayList<>(members.size());
        for (GeneratorRowAsset row : members) {
            Map<String, JsonPrimitive> bindings = row.bindings();
            if (!bindings.isEmpty()) {
                out.add(bindings);
            }
        }
        return out;
    }

    /** Note that a generator of {@code generatorType} walked {@code set}, so a move of its rows re-expands it. */
    static void walked(@Nonnull Class<?> generatorType, @Nonnull String set) {
        WALKED.computeIfAbsent(generatorType, type -> ConcurrentHashMap.newKeySet())
                .add(set.toLowerCase(Locale.ROOT));
    }

    /**
     * How the store of {@code generatorType}'s generators expands them again, run by {@link #settle} when a set one
     * of them walked has moved. Registered once at setup by whoever registers the store; registering a type again
     * replaces its re-fold. A store that never registers one still reads the rows at its own next fold.
     */
    public static void onRefold(@Nonnull Class<? extends GeneratorSpec> generatorType, @Nonnull Runnable refold) {
        REFOLDS.put(generatorType, refold);
    }

    /**
     * Compare every set's rows with the last settle's and re-expand each store whose generators walked a set that
     * moved (a row added, removed, retuned, switched or reordered). Run after every fold of the rows: the pack
     * layer's load and every read of the owner file ({@link GeneratorRowConfig#reloadOwner}). A re-fold that throws
     * costs that store's re-fold and one line, never the others'.
     */
    public static void settle() {
        List<Runnable> due = new ArrayList<>();
        synchronized (SETTLE_LOCK) {
            Map<String, List<Map<String, JsonPrimitive>>> now = bySet();
            Set<String> moved = new HashSet<>(now.keySet());
            moved.addAll(settled.keySet());
            moved.removeIf(set -> Objects.equals(now.get(set), settled.get(set)));
            settled = now;
            if (moved.isEmpty()) {
                return;
            }
            for (Map.Entry<Class<?>, Runnable> refold : REFOLDS.entrySet()) {
                Set<String> walked = WALKED.get(refold.getKey());
                if (walked != null && walked.stream().anyMatch(moved::contains)) {
                    due.add(refold.getValue());
                }
            }
        }
        for (Runnable refold : due) {
            try {
                refold.run();
            } catch (Throwable t) {
                SafeLog.warn("[generator] a store's generators could not be expanded again after their rows "
                        + "moved, so its families keep what they wrote before until it loads again", t);
            }
        }
    }

    /** Every set's rows as {@link #rows} answers them, by lower-cased set. */
    @Nonnull
    private static Map<String, List<Map<String, JsonPrimitive>>> bySet() {
        Set<String> sets = new HashSet<>();
        for (GeneratorRowAsset row : GeneratorRowConfig.getInstance().all().values()) {
            if (row != null && row.getSet() != null) {
                sets.add(row.getSet().toLowerCase(Locale.ROOT));
            }
        }
        Map<String, List<Map<String, JsonPrimitive>>> out = new LinkedHashMap<>();
        for (String set : sets) {
            out.put(set, rows(set));
        }
        return out;
    }

    /** Forget every walk, re-fold and settle: a test's clean slate. */
    static void forget() {
        synchronized (SETTLE_LOCK) {
            WALKED.clear();
            REFOLDS.clear();
            settled = Map.of();
        }
    }
}
