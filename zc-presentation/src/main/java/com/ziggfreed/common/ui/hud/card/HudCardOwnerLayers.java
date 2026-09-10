package com.ziggfreed.common.ui.hud.card;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;

/**
 * The SERVER OWNER's last word on the shared card look, at
 * {@code mods/ziggfreedcommon/hud-cards.json}: a bare map from a record id to the leaves that
 * record should read differently, decoded against the packs' own answer through the same codec
 * the file uses, exactly like every other owner file this library reads.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/hud-cards.json
 * { "Default": { "Color": "#ffffffb8" } }
 * }</pre>
 *
 * <p>Read from the store's own load event, since an owner entry has nothing to inherit from until
 * the packs have landed.
 */
public final class HudCardOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file over the card look. */
    public static final String CARDS_FILE = "hud-cards.json";

    private static final String LOG_TAG = "hud";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private HudCardOwnerLayers() {
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

    /** (Re)read {@code hud-cards.json} into the card fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(CARDS_FILE), HudCardAsset.class,
                HudCardAsset.CODEC, HudCardConfig.getInstance(), "card");
    }
}
