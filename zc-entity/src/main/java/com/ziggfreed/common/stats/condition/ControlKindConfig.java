package com.ziggfreed.common.stats.condition;

import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of {@link ControlKindAsset}, keyed by kind id. The library ships its
 * twelve kinds in its own jar's asset pack, so they ride the pack layer; the owner layer, from
 * {@code mods/ziggfreedcommon/}{@value #OWNER_FILE}, wins over both, and the Control Kinds screen on {@code /zigadmin}
 * writes it ({@link #BINDING}). Every merge marks the kind rules ({@link ControlKinds}).
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/control-kinds.json
 * { "Slow": { "MaxSeconds": 6 }, "Taunt": { "OnBossOrElite": "Usual" } }
 * }</pre>
 */
public final class ControlKindConfig extends AbstractKeyedAssetConfig<ControlKindAsset> {

    /** The owner file over the kinds. */
    public static final String OWNER_FILE = "control-kinds.json";

    /** The file's id as an owner form names it (zc-core's {@code OwnerFileBindings}): its path under {@code mods/}. */
    public static final String BINDING = "ziggfreedcommon/" + OWNER_FILE;

    private static final ControlKindConfig INSTANCE = new ControlKindConfig();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private ControlKindConfig() {
    }

    @Nonnull
    public static ControlKindConfig getInstance() {
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
        OwnerLayerReader.apply("controlkind", file(), ControlKindAsset.class, ControlKindAsset.CODEC, INSTANCE,
                "kind of control");
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, ControlKindAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        ControlKinds.markDirty();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, ControlKindAsset> layer) {
        super.mergePackLayer(layer);
        ControlKinds.markDirty();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, ControlKindAsset> layer) {
        super.mergeOwnerLayer(layer);
        ControlKinds.markDirty();
    }
}
