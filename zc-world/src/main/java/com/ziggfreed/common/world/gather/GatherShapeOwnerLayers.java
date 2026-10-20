package com.ziggfreed.common.world.gather;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's last word on the gather shapes, at {@code mods/ziggfreedcommon/gather-shapes.json}: a bare map
 * from a shape id to the leaves that shape should read differently, decoded against the packs' own answer through the
 * shape's own codec.
 *
 * <pre>{@code
 * { "My_Pack_Vein": { "Count": { "Base": 8 } },
 *   "My_Pack_Fell": { "Enabled": false } }
 * }</pre>
 *
 * <p>An id no pack authored stands on its own as a new shape. Read from the store's own load event. An entry follows
 * the mod gate as a shape file does: one gated on a missing mod, or retuning a shape the gate kept out, is dropped.
 */
public final class GatherShapeOwnerLayers {

    /** The owner file over the shapes. */
    public static final String FILE = "gather-shapes.json";

    private static final String LOG_TAG = "gather";

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private GatherShapeOwnerLayers() {
    }

    /** Point the owner file at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** The owner file being read. */
    @Nonnull
    public static Path file() {
        return directory.resolve(FILE);
    }

    /** (Re)read the owner file into the shape fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, file(), GatherShapeAsset.class, GatherShapeAsset.CODEC,
                GatherShapeConfig.getInstance(), "gather shape", s -> GateSpec.missingMod(s.requires()));
    }
}
