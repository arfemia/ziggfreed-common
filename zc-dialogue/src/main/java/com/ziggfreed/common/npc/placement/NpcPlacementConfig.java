package com.ziggfreed.common.npc.placement;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The {@code defaults < pack < owner} fold of every {@link NpcPlacementAsset}, and the pool the
 * reconciler walks when it decides what should be standing in a world.
 *
 * <p>Every layer write clears the per-world sweep debounce ({@link NpcPlacementReconciler}) and
 * the resolved-position cache, so a reload takes effect on the next sweep rather than waiting for
 * a world to be entered fresh. Both invalidations live on the merge methods themselves, so no
 * caller can forget them.
 */
public final class NpcPlacementConfig extends AbstractKeyedAssetConfig<NpcPlacementAsset> {

    private static final NpcPlacementConfig INSTANCE = new NpcPlacementConfig();

    private NpcPlacementConfig() {
    }

    @Nonnull
    public static NpcPlacementConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, NpcPlacementAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        onPoolChanged();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, NpcPlacementAsset> layer) {
        super.mergePackLayer(layer);
        onPoolChanged();
        logFindings();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, NpcPlacementAsset> layer) {
        super.mergeOwnerLayer(layer);
        onPoolChanged();
        logFindings();
    }

    /**
     * Drop every derived cache that was computed from the previous pool. Also safe (and correct)
     * to call from a consumer's own config-reload command.
     */
    public void onPoolChanged() {
        NpcPlacementReconciler.clearDebounce();
        NpcPlacementPositionCache.invalidateAll();
        NpcIdentities.invalidate();
    }

    /** Audit every folded placement (see {@link NpcPlacementValidator}). */
    @Nonnull
    public List<Finding> audit() {
        return NpcPlacementValidator.audit(all().values());
    }

    /** Log this config's findings once per fold: an error as a warning line, anything else at info. */
    public void logFindings() {
        ValidationReport.logAll("[placement]", audit(), SafeLog::warn, SafeLog::info);
    }
}
