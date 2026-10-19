package com.ziggfreed.common.usegate;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.util.JsonOverrideWriter;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The SERVER OWNER's last word on use gates, at {@code mods/ziggfreedcommon/use-gates.json}: a bare map from a
 * gate id to the leaves that gate should read differently, decoded against the packs' own answer through the same
 * codec the pack file uses, exactly like the gear-set and economy owner files.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/use-gates.json
 * {
 *   "Iron_Pickaxe": { "Enabled": false }
 * }
 * }</pre>
 *
 * <p>Read from the store's own load event (an owner entry has nothing to inherit from until the packs have
 * landed) and again after an admin switches a gate on the Use Gates page. An entry follows the mod gate as a
 * gate file does: one gated on a missing mod, or retuning a gate the mod gate kept out, is dropped.
 */
public final class UseGateOwnerLayers {

    /** Where a server owner's file lives. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file over the folded gates. */
    public static final String FILE = "use-gates.json";

    private static final String LOG_TAG = "usegate";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private UseGateOwnerLayers() {
    }

    /** Point the owner file at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner file is being read from. */
    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** (Re)read {@code use-gates.json} into the fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), UseGateAsset.class,
                UseGateAsset.CODEC, UseGateConfig.getInstance(), "use gate",
                g -> GateSpec.missingMod(g.requires()));
    }

    /**
     * Switch one gate on or off in the owner file (its {@code Enabled} leaf, every sibling kept) and read the
     * file back into the fold. False when the write failed, which leaves the file and the fold as they were.
     */
    public static boolean setEnabled(@Nonnull String gateId, boolean enabled) {
        if (gateId.isBlank()) {
            return false;
        }
        boolean written = JsonOverrideWriter.setLeaf(directory.resolve(FILE), gateId.trim() + ".Enabled", enabled);
        if (written) {
            reload();
        }
        return written;
    }
}
