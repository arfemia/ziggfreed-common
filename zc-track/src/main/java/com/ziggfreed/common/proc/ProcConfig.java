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
 *
 * <p><b>The order contract.</b> The procs answering one moment run in the order of their ids compared lower-cased
 * ({@code String} order after a {@code Locale.ROOT} fold), whatever case the files spell them in and whatever order
 * the packs load in: content relies on it (a proc that breaks a freeze is named to run before every proc that lays
 * one), and {@code ProcOrderTest} pins it.
 */
public final class ProcConfig extends AbstractKeyedAssetConfig<ProcAsset> {

    /** The store's mod-gate label, which its drop lines carry. */
    public static final String MOD_GATE_STORE = "Procs";

    private static final ProcConfig INSTANCE = new ProcConfig();

    /** One live proc: its id (lower-cased, the cooldown's key) and its file. */
    public record Def(@Nonnull String key, @Nonnull ProcAsset asset) {
    }

    /**
     * One fold: the live procs by trigger, whether any asks a move's root (so chain starts are recorded), and the
     * effect groups the effect moments watch on the holder and on the foes they struck.
     */
    record Folded(@Nonnull Map<ProcTrigger, List<Def>> byTrigger, boolean asksRoots,
            @Nonnull List<ProcAsset.Effect> ownWatch, @Nonnull List<ProcAsset.Effect> foeWatch, double allyReach) {
    }

    private volatile Folded folded;

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
        List<Def> defs = folded().byTrigger().get(trigger);
        return defs == null ? List.of() : defs;
    }

    /** Does any live proc ask which RootInteraction a move came from? */
    public boolean asksRoots() {
        return folded().asksRoots();
    }

    /** The effect groups live effect procs watch: on the foes the holder struck, or on the holder. */
    @Nonnull
    public List<ProcAsset.Effect> watched(boolean onFoe) {
        Folded local = folded();
        return onFoe ? local.foeWatch() : local.ownWatch();
    }

    /** The farthest any live AllyHitIncoming proc asks an ally to stand, in blocks; 0 for none. */
    public double allyReach() {
        return folded().allyReach();
    }

    @Nonnull
    private Folded folded() {
        Folded local = folded;
        if (local == null) {
            local = foldAll(all());
            folded = local;
        }
        return local;
    }

    /** The live procs of {@code all} by trigger, each list in id order (compared lower-cased). Pure. */
    @Nonnull
    static Map<ProcTrigger, List<Def>> fold(@Nonnull Map<String, ProcAsset> all) {
        return foldAll(all).byTrigger();
    }

    @Nonnull
    private static Folded foldAll(@Nonnull Map<String, ProcAsset> all) {
        TreeMap<String, ProcAsset> sorted = new TreeMap<>();
        for (Map.Entry<String, ProcAsset> entry : all.entrySet()) {
            if (entry.getKey() != null) {
                sorted.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        Map<ProcTrigger, List<Def>> out = new EnumMap<>(ProcTrigger.class);
        boolean asksRoots = false;
        double allyReach = 0.0;
        List<ProcAsset.Effect> own = new ArrayList<>();
        List<ProcAsset.Effect> foe = new ArrayList<>();
        for (Map.Entry<String, ProcAsset> entry : sorted.entrySet()) {
            ProcAsset proc = entry.getValue();
            ProcTrigger trigger = proc == null ? null : proc.trigger();
            if (proc == null || proc.isAbstract() || !proc.isEnabled() || trigger == null) {
                continue;
            }
            out.computeIfAbsent(trigger, t -> new ArrayList<>()).add(new Def(entry.getKey(), proc));
            if (trigger == ProcTrigger.ALLY_HIT_INCOMING) {
                allyReach = Math.max(allyReach, proc.allyRadius());
            }
            ProcAsset.Ability ability = proc.ability();
            asksRoots |= ability != null && !ability.roots().isEmpty();
            ProcAsset.Effect effect = proc.effect();
            if (trigger.aboutEffect() && effect != null) {
                (effect.onFoe() ? foe : own).add(effect);
            }
        }
        Map<ProcTrigger, List<Def>> frozen = new EnumMap<>(ProcTrigger.class);
        for (Map.Entry<ProcTrigger, List<Def>> entry : out.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new Folded(frozen, asksRoots, List.copyOf(own), List.copyOf(foe), allyReach);
    }
}
