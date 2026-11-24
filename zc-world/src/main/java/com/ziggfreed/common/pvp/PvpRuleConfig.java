package com.ziggfreed.common.pvp;

import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of every {@link PvpRuleAsset}, keyed by file id. The library ships
 * {@code Default.json} (every world, the shipped numbers) in its own jar's asset pack, so it rides the pack layer; the
 * owner layer, from {@code mods/ziggfreedcommon/}{@value #OWNER_FILE} (an id to the leaves that file should read
 * differently, decoded against the packs' answer), wins over both, and the PvP Rules screen on {@code /zigadmin} writes
 * it ({@link #BINDING}, through {@link PvpRuleForm}). Every merge moves {@link PvpWorldRules}' generation, so each world
 * reads its rules again.
 */
public final class PvpRuleConfig extends AbstractKeyedAssetConfig<PvpRuleAsset> {

    /** The owner file over the rule files. */
    public static final String OWNER_FILE = "pvp-rules.json";

    /** The file's id as an owner form names it: its path under {@code mods/}. */
    public static final String BINDING = "ziggfreedcommon/" + OWNER_FILE;

    private static final PvpRuleConfig INSTANCE = new PvpRuleConfig();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private PvpRuleConfig() {
    }

    @Nonnull
    public static PvpRuleConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** The owner file, for the binding that writes it. */
    @Nonnull
    public static Path file() {
        return directory.resolve(OWNER_FILE);
    }

    /** (Re)read the owner file into the owner layer. From the store's own load event, after the packs. */
    public static void reloadOwner() {
        OwnerLayerReader.apply("pvp", file(), PvpRuleAsset.class, PvpRuleAsset.CODEC, INSTANCE, "PvP rule");
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, PvpRuleAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        PvpWorldRules.invalidate();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, PvpRuleAsset> layer) {
        super.mergePackLayer(layer);
        PvpWorldRules.invalidate();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, PvpRuleAsset> layer) {
        super.mergeOwnerLayer(layer);
        PvpWorldRules.invalidate();
    }
}
