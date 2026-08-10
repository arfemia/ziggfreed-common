package com.ziggfreed.common.world;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.world.WorldSelectorValidator.Issue;

/**
 * The {@code defaults < pack < owner} fold of every {@link WorldSelectorAsset}, and the pool
 * {@link WorldIdentity} resolves a world's names from.
 *
 * <p><b>Every layer write invalidates {@link WorldIdentity}.</b> That is not an optimization
 * detail, it is the difference between working and silently broken: the primary world's
 * {@code AddWorldEvent} fires during universe boot, BEFORE the asset load event folds this
 * config, so a world resolved before the fold caches an EMPTY name set for the life of the
 * process and {@code Names: ["primary"]} never matches anything anywhere. The invalidation lives
 * on the merge methods themselves so no caller can forget it.
 */
public final class WorldSelectorConfig extends AbstractKeyedAssetConfig<WorldSelectorDef> {

    private static final WorldSelectorConfig INSTANCE = new WorldSelectorConfig();

    private WorldSelectorConfig() {
    }

    @Nonnull
    public static WorldSelectorConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, WorldSelectorDef> jarDefaults) {
        super.loadDefaults(jarDefaults);
        WorldIdentity.invalidateAll();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, WorldSelectorDef> layer) {
        super.mergePackLayer(layer);
        WorldIdentity.invalidateAll();
        logFindings();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, WorldSelectorDef> layer) {
        super.mergeOwnerLayer(layer);
        WorldIdentity.invalidateAll();
        logFindings();
    }

    /**
     * Audit every folded selector: missing / blank names, and a selector that names something but
     * can never match a world. Findings are neutral values a consumer can surface in its own
     * validation command; {@link #logFindings()} is the always-on baseline.
     */
    @Nonnull
    public List<Issue> validate() {
        return WorldSelectorValidator.validateAll(all().values());
    }

    /** Log this config's findings once per fold (guarded - a unit JVM has no log manager). */
    public void logFindings() {
        for (Issue issue : validate()) {
            try {
                String line = "WorldSelector '" + issue.sourceId() + "' [" + issue.code() + "]: " + issue.message();
                if (issue.severity() == WorldSelectorValidator.Severity.ERROR) {
                    ZiggfreedCommonPlugin.LOGGER.atWarning().log(line);
                } else {
                    ZiggfreedCommonPlugin.LOGGER.atInfo().log(line);
                }
            } catch (Throwable ignored) {
                // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
            }
        }
    }
}
