package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link HeldEffectAsset}, and the live ones in id order (skeletons,
 * switched-off files and files naming no effect left out), rebuilt on the first read after any layer merges. Files may
 * gate on a mod through their top-level {@code Requires}. Every effect a file names, live or switched off, is listed
 * too: the one taken off when its hold is not met.
 */
public final class HeldEffectConfig extends AbstractKeyedAssetConfig<HeldEffectAsset> {

    /** The store's mod-gate label, which its drop lines carry. */
    public static final String MOD_GATE_STORE = "HeldEffects";

    private static final HeldEffectConfig INSTANCE = new HeldEffectConfig();

    private volatile List<HeldEffectAsset> folded;

    private HeldEffectConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static HeldEffectConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, HeldEffectAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        folded = null;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, HeldEffectAsset> layer) {
        super.mergePackLayer(layer);
        folded = null;
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, HeldEffectAsset> layer) {
        super.mergeOwnerLayer(layer);
        folded = null;
    }

    /** Every non-skeleton file naming an effect, in id order, switched-off ones included (their effect comes off). */
    @Nonnull
    public List<HeldEffectAsset> held() {
        List<HeldEffectAsset> local = folded;
        if (local == null) {
            local = fold(all());
            folded = local;
        }
        return local;
    }

    /** The non-skeleton files of {@code all} naming an effect, in id order. Pure. */
    @Nonnull
    static List<HeldEffectAsset> fold(@Nonnull Map<String, HeldEffectAsset> all) {
        List<HeldEffectAsset> out = new ArrayList<>();
        for (HeldEffectAsset held : new TreeMap<>(all).values()) {
            if (held != null && !held.isAbstract() && held.effect() != null) {
                out.add(held);
            }
        }
        return List.copyOf(out);
    }
}
