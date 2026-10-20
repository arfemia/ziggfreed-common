package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link ProcAsset}, and the live procs each trigger asks, in id
 * order (skeletons, switched-off procs and procs naming no known trigger left out), rebuilt on the first read after
 * any layer merges. Files may gate on a mod through their top-level {@code Requires} (its plain mod presence decided
 * at the fold, the rest asked of the player when the moment comes).
 */
public final class ProcConfig extends AbstractKeyedAssetConfig<ProcAsset> {

    /** The store's mod-gate label, which its drop lines carry. */
    public static final String MOD_GATE_STORE = "Procs";

    private static final ProcConfig INSTANCE = new ProcConfig();

    /** One live proc: its id (lower-cased, the cooldown's key) and its file. */
    public record Def(@Nonnull String key, @Nonnull ProcAsset asset) {
    }

    private volatile Map<ProcTrigger, List<Def>> folded;

    private ProcConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static ProcConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, ProcAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        folded = null;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, ProcAsset> layer) {
        super.mergePackLayer(layer);
        folded = null;
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, ProcAsset> layer) {
        super.mergeOwnerLayer(layer);
        folded = null;
    }

    /** The live procs answering {@code trigger}, in id order. Empty, never null. */
    @Nonnull
    public List<Def> forTrigger(@Nonnull ProcTrigger trigger) {
        List<Def> defs = folded().get(trigger);
        return defs == null ? List.of() : defs;
    }

    @Nonnull
    private Map<ProcTrigger, List<Def>> folded() {
        Map<ProcTrigger, List<Def>> local = folded;
        if (local == null) {
            local = fold(all());
            folded = local;
        }
        return local;
    }

    /** The live procs of {@code all} by trigger, each list in id order. Pure. */
    @Nonnull
    static Map<ProcTrigger, List<Def>> fold(@Nonnull Map<String, ProcAsset> all) {
        Map<ProcTrigger, List<Def>> out = new EnumMap<>(ProcTrigger.class);
        for (Map.Entry<String, ProcAsset> entry : new TreeMap<>(all).entrySet()) {
            ProcAsset proc = entry.getValue();
            ProcTrigger trigger = proc == null ? null : proc.trigger();
            if (proc == null || proc.isAbstract() || !proc.isEnabled() || trigger == null) {
                continue;
            }
            out.computeIfAbsent(trigger, t -> new ArrayList<>())
                    .add(new Def(entry.getKey().toLowerCase(Locale.ROOT), proc));
        }
        Map<ProcTrigger, List<Def>> frozen = new EnumMap<>(ProcTrigger.class);
        for (Map.Entry<ProcTrigger, List<Def>> entry : out.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return frozen;
    }
}
