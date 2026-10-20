package com.ziggfreed.common.world.gather;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link GatherShapeAsset}, and the shapes each moment asks, in id
 * order (switched-off shapes and shapes naming an unknown moment left out), rebuilt on the first read after any layer
 * merges. Files may gate on a mod through their top-level {@code Requires} (its plain mod presence decided at the
 * fold, the rest asked of the player when the shape would run).
 */
public final class GatherShapeConfig extends AbstractKeyedAssetConfig<GatherShapeAsset> {

    /** The store's mod-gate label, which its drop lines carry. */
    public static final String MOD_GATE_STORE = "GatherShapes";

    private static final GatherShapeConfig INSTANCE = new GatherShapeConfig();

    /** One fold: the live shapes of each moment, in id order. */
    private record Folded(@Nonnull List<GatherShapeAsset> breaks, @Nonnull List<GatherShapeAsset> instants,
            @Nonnull List<GatherShapeAsset> harvests) {
    }

    private volatile Folded folded;

    private GatherShapeConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static GatherShapeConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, GatherShapeAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        invalidate();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, GatherShapeAsset> layer) {
        super.mergePackLayer(layer);
        invalidate();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, GatherShapeAsset> layer) {
        super.mergeOwnerLayer(layer);
        invalidate();
    }

    /** The live {@code Break} shapes that take blocks beyond the first, in id order. */
    @Nonnull
    public List<GatherShapeAsset> breakShapes() {
        return folded().breaks();
    }

    /** The live {@code Break} shapes whose first block breaks in one hit, in id order. */
    @Nonnull
    public List<GatherShapeAsset> instantShapes() {
        return folded().instants();
    }

    /** The live {@code Harvest} shapes, in id order. */
    @Nonnull
    public List<GatherShapeAsset> harvestShapes() {
        return folded().harvests();
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

    /** The shapes of {@code all} each moment asks, in id order. Pure. */
    @Nonnull
    static Folded fold(@Nonnull Map<String, GatherShapeAsset> all) {
        List<GatherShapeAsset> breaks = new ArrayList<>();
        List<GatherShapeAsset> instants = new ArrayList<>();
        List<GatherShapeAsset> harvests = new ArrayList<>();
        for (GatherShapeAsset shape : new TreeMap<>(all).values()) {
            if (shape == null || !shape.isEnabled() || shape.moment() == null) {
                continue;
            }
            if (shape.moment() == GatherShapeAsset.Moment.HARVEST) {
                if (shape.shape() != null && shape.shape().walk() != null) {
                    harvests.add(shape);
                }
                continue;
            }
            if (shape.instant()) {
                instants.add(shape);
            }
            if (shape.shape() != null && shape.shape().walk() != null) {
                breaks.add(shape);
            }
        }
        return new Folded(List.copyOf(breaks), List.copyOf(instants), List.copyOf(harvests));
    }

    private void invalidate() {
        folded = null;
    }
}
