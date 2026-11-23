package com.ziggfreed.common.ui.hud.statbar;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The SERVER OWNER's last word on the stat bars, at {@code mods/ziggfreedcommon/stat-bars.json}: a map from a bar id
 * to the leaves that bar should read differently, decoded against the packs' own answer through the bar's codec.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/stat-bars.json
 * { "Your_Focus_Bar": { "Enabled": false } }
 * }</pre>
 *
 * <p>Read from the store's own load event (an owner entry has nothing to inherit from until the packs have landed)
 * and again after the Stat Bars screen saves ({@link #binding}); every online player's bars repaint after either.
 */
public final class StatBarOwnerLayers {

    /** The owner file, as an owner form's {@code File} names it. */
    public static final String BINDING = "ziggfreedcommon/stat-bars.json";

    /** The owner file's name in {@link OwnerFiles#DIRECTORY}. */
    public static final String FILE = "stat-bars.json";

    private static final String LOG_TAG = "hud";

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private StatBarOwnerLayers() {
    }

    /** Point the owner file at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner file is read from and written to. */
    @Nonnull
    public static Path file() {
        return directory.resolve(FILE);
    }

    /** (Re)read {@code stat-bars.json} into the fold's owner layer and repaint every online player's bars. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, file(), StatBarAsset.class, StatBarAsset.CODEC, StatBarConfig.getInstance(),
                "stat bar");
        StatBars.repaintAllOnline();
    }

    /** The owner file as an in-game editor may write it: its Default read off the packs' bars, its reload ours. */
    @Nonnull
    public static OwnerFileBinding binding() {
        return OwnerFileBinding.keyedStore(BINDING, StatBarOwnerLayers::file, StatBarConfig.getInstance(),
                StatBarAsset.CODEC, StatBarOwnerLayers::reload);
    }
}
