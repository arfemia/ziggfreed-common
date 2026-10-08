package com.ziggfreed.common.loot.trigger;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.asset.PresenceRequiresCodec;

/**
 * The server owner's last word on the library's bonus rows, at
 * {@code mods/ziggfreedcommon/bonus-rows.json}: a bare map from a row id to the leaves that row
 * should read differently, decoded against the packs' own answer through the row's own codec.
 *
 * <pre>{@code
 * { "My_Pack_Geode": { "Enabled": false },
 *   "My_Pack_Pumpkin": { "Chance": { "Base": 15.0 } } }
 * }</pre>
 *
 * <p>An id no pack authored stands on its own as a new row. Read from the store's own load event,
 * since an owner entry has nothing to inherit from until the packs have landed. An entry follows the
 * mod gate as a row file does: one gated on a missing mod, or retuning a row the gate kept out, is
 * dropped.
 */
public final class BonusRowOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Path.of("mods", "ziggfreedcommon");

    /** The owner file over the rows. */
    public static final String FILE = "bonus-rows.json";

    private static final String LOG_TAG = "loot";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private BonusRowOwnerLayers() {
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

    /** (Re)read the owner file into the row fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, file(), BonusRowAsset.class, BonusRowAsset.CODEC,
                BonusRowConfig.getInstance(), "bonus row", r -> PresenceRequiresCodec.missingMod(r.getRequires()));
    }
}
