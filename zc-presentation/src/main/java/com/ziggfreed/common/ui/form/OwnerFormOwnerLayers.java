package com.ziggfreed.common.ui.form;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's word on the owner forms, at {@code mods/ziggfreedcommon/owner-forms.json}: a map from a form id to
 * the leaves that form should read differently, decoded against the packs' own answer through the same codec.
 *
 * <pre>{@code
 * { "Boost_Settings": { "Order": 5 } }   // move a tile up the menu
 * }</pre>
 *
 * <p>Read from the store's own load event, after the packs have landed. An entry follows the mod gate as a form file
 * does: one gated on a missing mod, or retuning a form the gate kept out, is dropped.
 */
public final class OwnerFormOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file over the forms. */
    public static final String FILE = "owner-forms.json";

    private static final String LOG_TAG = "owner";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private OwnerFormOwnerLayers() {
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** The owner file over the forms. */
    @Nonnull
    public static Path file() {
        return directory.resolve(FILE);
    }

    /** (Re)read {@code owner-forms.json} into the forms fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, file(), OwnerFormAsset.class, OwnerFormAsset.CODEC,
                OwnerFormConfig.getInstance(), "owner form", f -> GateSpec.missingMod(f.requires()));
    }
}
