package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link CounterAsset}: the live counters by id (lower-cased) and,
 * for each moment, the counters it clears, rebuilt on the first read after any layer merges. Files may gate on a mod
 * through their top-level {@code Requires} (its plain mod presence decided at the fold, the rest asked of the holder
 * on every pass: what keeps its stacks).
 */
public final class CounterConfig extends AbstractKeyedAssetConfig<CounterAsset> {

    /** The store's mod-gate label, which its drop lines carry. */
    public static final String MOD_GATE_STORE = "Counters";

    private static final CounterConfig INSTANCE = new CounterConfig();

    /** One live counter: its id, lower-cased (its modifier key and its factor Param), and its file. */
    public record Def(@Nonnull String key, @Nonnull CounterAsset asset) {
    }

    /** One fold: every live counter by key, and the counters each moment clears. */
    record Folded(@Nonnull Map<String, Def> byKey, @Nonnull Map<ProcTrigger, List<Def>> clearedBy) {
    }

    private volatile Folded folded;

    private CounterConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static CounterConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, CounterAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        folded = null;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, CounterAsset> layer) {
        super.mergePackLayer(layer);
        folded = null;
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, CounterAsset> layer) {
        super.mergeOwnerLayer(layer);
        folded = null;
    }

    /** The live counter {@code id} names (any case), or null. */
    @Nullable
    public Def live(@Nullable String id) {
        return id == null ? null : folded().byKey().get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Every live counter, by key. */
    @Nonnull
    public Map<String, Def> liveAll() {
        return folded().byKey();
    }

    /** The live counters {@code trigger} empties. Empty, never null. */
    @Nonnull
    public List<Def> clearedBy(@Nonnull ProcTrigger trigger) {
        List<Def> defs = folded().clearedBy().get(trigger);
        return defs == null ? List.of() : defs;
    }

    @Nonnull
    private Folded folded() {
        Folded local = folded;
        if (local == null) {
            local = fold(all());
            folded = local;
        }
        return local;
    }

    /** The live counters of {@code all}, and what clears each, in id order. Pure. */
    @Nonnull
    static Folded fold(@Nonnull Map<String, CounterAsset> all) {
        Map<String, Def> byKey = new LinkedHashMap<>();
        Map<ProcTrigger, List<Def>> clears = new EnumMap<>(ProcTrigger.class);
        for (Map.Entry<String, CounterAsset> entry : new TreeMap<>(all).entrySet()) {
            CounterAsset counter = entry.getValue();
            if (counter == null || counter.isAbstract() || !counter.isEnabled()) {
                continue;
            }
            Def def = new Def(entry.getKey().toLowerCase(Locale.ROOT), counter);
            byKey.put(def.key(), def);
            for (ProcTrigger trigger : counter.clearedBy()) {
                clears.computeIfAbsent(trigger, t -> new ArrayList<>()).add(def);
            }
        }
        Map<ProcTrigger, List<Def>> frozen = new EnumMap<>(ProcTrigger.class);
        for (Map.Entry<ProcTrigger, List<Def>> entry : clears.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new Folded(Map.copyOf(byKey), frozen);
    }
}
