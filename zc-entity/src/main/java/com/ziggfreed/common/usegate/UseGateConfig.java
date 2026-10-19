package com.ziggfreed.common.usegate;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The runtime table of authored {@link UseGateAsset}s, folded {@code defaults < pack < owner} like every other
 * keyed asset type and looked up by gate id without regard to case, plus the derived {@link UseGateIndex} every
 * moment reads, rebuilt lazily after any layer moves. The index is dropped from the merge methods themselves, so
 * a re-import, an owner reload and a test seeding the config directly all leave the next moment reading the
 * gates that are really there.
 */
public final class UseGateConfig extends AbstractKeyedAssetConfig<UseGateAsset> {

    /** The store's mod-gate label, which its drop lines carry. */
    public static final String MOD_GATE_STORE = "UseGates";

    private static final UseGateConfig INSTANCE = new UseGateConfig();

    private volatile UseGateIndex index;

    @Nonnull
    public static UseGateConfig getInstance() {
        return INSTANCE;
    }

    private UseGateConfig() {
        super(MOD_GATE_STORE);
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, UseGateAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        index = null;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, UseGateAsset> layer) {
        super.mergePackLayer(layer);
        index = null;
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, UseGateAsset> layer) {
        super.mergeOwnerLayer(layer);
        index = null;
    }

    /** The per-axis index over the folded view, built on first read after a change. */
    @Nonnull
    public UseGateIndex index() {
        UseGateIndex current = index;
        if (current == null) {
            current = UseGateIndex.of(all().values());
            index = current;
        }
        return current;
    }
}
