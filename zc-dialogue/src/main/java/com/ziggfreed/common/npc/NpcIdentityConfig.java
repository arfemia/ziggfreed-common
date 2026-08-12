package com.ziggfreed.common.npc;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The {@code defaults < pack < owner} fold of every {@link NpcIdentityAsset}, and the overlay table
 * {@link NpcIdentities} consults after a placement and before the naming convention.
 *
 * <p>Every layer write drops the resolved identity index, so a reload is visible on the next lookup
 * rather than at the next restart. That invalidation lives on the merge methods themselves, so no
 * caller can forget it.
 */
public final class NpcIdentityConfig extends AbstractKeyedAssetConfig<NpcIdentityAsset> {

    private static final NpcIdentityConfig INSTANCE = new NpcIdentityConfig();

    private NpcIdentityConfig() {
    }

    @Nonnull
    public static NpcIdentityConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, NpcIdentityAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        NpcIdentities.invalidate();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, NpcIdentityAsset> layer) {
        super.mergePackLayer(layer);
        NpcIdentities.invalidate();
        logFindings();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, NpcIdentityAsset> layer) {
        super.mergeOwnerLayer(layer);
        NpcIdentities.invalidate();
        logFindings();
    }

    /** Audit every folded identity overlay (see {@link NpcIdentityValidator}). */
    @Nonnull
    public List<Finding> audit() {
        return NpcIdentityValidator.audit(all().values());
    }

    /** Log this config's findings once per fold: an error as a warning line, anything else at info. */
    public void logFindings() {
        ValidationReport.logAll("[identity]", audit(), SafeLog::warn, SafeLog::info);
    }
}
