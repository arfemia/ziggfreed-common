package com.ziggfreed.common.stats.gearset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.progress.gate.StoreGates;

/**
 * The runtime table of authored {@link GearSetAsset}s, folded {@code defaults < pack < owner} like
 * every other keyed asset type and looked up by set id without regard to case, plus the derived
 * {@link GearSetIndex} the recompute reads (item id to sets), rebuilt lazily after any layer moves.
 *
 * <p>The index is dropped from the merge methods themselves rather than by whoever called them, so
 * a re-import, an owner reload and a test seeding the config directly all leave the next recompute
 * reading the sets that are really there. Each merge also names, once at INFO, every set whose
 * {@code Requires} reads more than mod presence ({@link StoreGates#reportWidened}).
 */
public final class GearSetConfig extends AbstractKeyedAssetConfig<GearSetAsset> {

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "GearSets";

    private static final GearSetConfig INSTANCE = new GearSetConfig();

    private volatile GearSetIndex index;

    @Nonnull
    public static GearSetConfig getInstance() {
        return INSTANCE;
    }

    private GearSetConfig() {
        super(MOD_GATE_STORE);
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, GearSetAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        changed();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, GearSetAsset> layer) {
        super.mergePackLayer(layer);
        changed();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, GearSetAsset> layer) {
        super.mergeOwnerLayer(layer);
        changed();
    }

    /** The item-to-sets index over the folded view, built on first read after a change. */
    @Nonnull
    public GearSetIndex index() {
        GearSetIndex current = index;
        if (current == null) {
            current = GearSetIndex.of(all().values());
            index = current;
        }
        return current;
    }

    /** Drop the derived index so the next read rebuilds it; the merges do this themselves. */
    public void dropIndex() {
        index = null;
    }

    private void changed() {
        index = null;
        List<String> widened = new ArrayList<>();
        for (Map.Entry<String, GearSetAsset> entry : all().entrySet()) {
            if (entry.getValue() != null && entry.getValue().liveRequires() != null) {
                widened.add(entry.getKey());
            }
        }
        StoreGates.reportWidened(MOD_GATE_STORE, widened);
    }
}
