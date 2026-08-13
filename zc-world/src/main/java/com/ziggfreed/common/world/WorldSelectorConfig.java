package com.ziggfreed.common.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The {@code defaults < pack < owner} fold of every {@link WorldSelectorAsset}, and the pool
 * {@link WorldIdentity} resolves a world's names from. The owner layer is
 * {@code mods/ziggfreedcommon/world-selectors.json}, read by {@link WorldSelectorOverrides} - the
 * one place a server owner can say "my main world is not called what the shipped file assumes".
 *
 * <p><b>Every layer write invalidates {@link WorldIdentity}.</b> That is not an optimization
 * detail, it is the difference between working and silently broken: the main world's
 * {@code AddWorldEvent} fires during universe boot, BEFORE the asset load event folds this
 * config, so a world resolved before the fold caches an EMPTY name set for the life of the
 * process and every {@code Names} reference silently never matches anything anywhere. The
 * invalidation lives on the merge methods themselves so no caller can forget it.
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
     * Audit every folded selector: missing / blank names, a selector that names something but can
     * never match a world, and a selector that matches none of the worlds this server has actually
     * loaded (the shape a renamed main world produces - see
     * {@link WorldSelectorValidator#validateAgainstWorlds}). The loaded-world pass reports nothing
     * when no world can be read, so an audit run before boot finishes stays silent rather than
     * flagging every file. Findings are neutral values a consumer can surface in its own validation
     * command; {@link #logFindings()} is the always-on baseline.
     */
    @Nonnull
    public List<Finding> audit() {
        List<Finding> findings = new ArrayList<>(WorldSelectorValidator.validateAll(all().values()));
        findings.addAll(WorldSelectorValidator.validateAgainstWorlds(
                all().values(), WorldIdentity.loadedWorlds()));
        return findings;
    }

    /**
     * Log this config's findings once per fold. Each sink call is guarded by
     * {@link ValidationReport#logAll}, which matters here because the flogger LOGGER throws in a
     * log-manager-less unit JVM.
     */
    public void logFindings() {
        ValidationReport.logAll("WorldSelector", audit(),
                line -> CommonLog.LOGGER.atWarning().log(line),
                line -> CommonLog.LOGGER.atInfo().log(line));
    }
}
