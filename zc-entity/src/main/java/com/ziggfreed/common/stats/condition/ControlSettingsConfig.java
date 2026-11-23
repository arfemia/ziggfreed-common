package com.ziggfreed.common.stats.condition;

import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of {@link ControlSettingsAsset}, keyed by record id. The library ships
 * {@code Default.json} in its own jar's asset pack, so it rides the pack layer; the owner layer, from
 * {@code mods/ziggfreedcommon/}{@value #OWNER_FILE}, wins over both, and the Crowd Control screen on {@code /zigadmin}
 * writes it ({@link #BINDING}). A server with no record at all reads every default ({@link #settings}). Every merge
 * marks the kind rules ({@link ControlKinds}).
 */
public final class ControlSettingsConfig extends AbstractKeyedAssetConfig<ControlSettingsAsset> {

    /** The owner file over the settings. */
    public static final String OWNER_FILE = "control-settings.json";

    /** The file's id as an owner form names it: its path under {@code mods/}. */
    public static final String BINDING = "ziggfreedcommon/" + OWNER_FILE;

    private static final ControlSettingsConfig INSTANCE = new ControlSettingsConfig();

    /** What a server with no record reads: every leaf unauthored. */
    private static final ControlSettingsAsset DEFAULTS = new ControlSettingsAsset();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private ControlSettingsConfig() {
    }

    @Nonnull
    public static ControlSettingsConfig getInstance() {
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
        OwnerLayerReader.apply("controlsettings", file(), ControlSettingsAsset.class, ControlSettingsAsset.CODEC,
                INSTANCE, "crowd control settings");
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, ControlSettingsAsset> layer) {
        super.mergePackLayer(layer);
        ControlKinds.markDirty();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, ControlSettingsAsset> layer) {
        super.mergeOwnerLayer(layer);
        ControlKinds.markDirty();
    }

    /** The folded record every landing reads, or the defaults when no layer authored one. */
    @Nonnull
    public ControlSettingsAsset settings() {
        return resolveOrDefault(ControlSettingsAsset.SHARED_ID, DEFAULTS);
    }
}
