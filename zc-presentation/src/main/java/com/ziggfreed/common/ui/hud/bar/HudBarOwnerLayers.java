package com.ziggfreed.common.ui.hud.bar;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;

/**
 * The SERVER OWNER's last word on the rows, the panels and the spots they can sit at, at
 * {@code mods/ziggfreedcommon/hud-bars.json}, {@code hud-bar-panels.json} and
 * {@code hud-bar-placements.json}: each a bare map from an id to the leaves that id should read
 * differently, decoded against the packs' own answer through the same codecs the files use, exactly
 * like every other owner file this library reads. An entry under an id no pack authored stands on
 * its own, so an owner retunes a row nobody shipped a file for by naming it in {@code Source}, or
 * adds a spot of their own by naming it here.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/hud-bars.json
 * { "wood_off":   { "Source": "WOOD", "Enabled": false },
 *   "slow_stone": { "Source": "STONE", "Color": "#c0c0c0", "LingerMs": 8000 } }
 *
 * // mods/ziggfreedcommon/hud-bar-panels.json
 * { "grid": { "Placement": "TopRight" },
 *   "default": { "Position": { "OffsetY": 260 }, "MaxVisible": 6 } }
 *
 * // mods/ziggfreedcommon/hud-bar-placements.json
 * { "bottomleft": { "Position": { "OffsetY": 40 } } }
 * }</pre>
 *
 * <p>Read from each store's own load event, since an owner entry has nothing to inherit from until
 * the packs have landed; the HUD settings page writes the panels file and re-reads it the same way.
 */
public final class HudBarOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file over the bars. */
    public static final String BARS_FILE = "hud-bars.json";

    /** The owner file over the panels. */
    public static final String PANELS_FILE = "hud-bar-panels.json";

    /** The owner file over the placements. */
    public static final String PLACEMENTS_FILE = "hud-bar-placements.json";

    private static final String LOG_TAG = "hud";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private HudBarOwnerLayers() {
    }

    /** Point the owner files at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner files are being read from. */
    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** The panels owner file, where the HUD settings page and {@code /zighud default} write. */
    @Nonnull
    public static Path panelsFile() {
        return directory.resolve(PANELS_FILE);
    }

    /** (Re)read {@code hud-bars.json} into the bar fold's owner layer. */
    public static void reloadBars() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(BARS_FILE), HudBarAsset.class,
                HudBarAsset.CODEC, HudBarConfig.getInstance(), "bar");
    }

    /** (Re)read {@code hud-bar-panels.json} into the panel fold's owner layer. */
    public static void reloadPanels() {
        OwnerLayerReader.apply(LOG_TAG, panelsFile(), HudBarPanelAsset.class,
                HudBarPanelAsset.CODEC, HudBarPanelConfig.getInstance(), "panel");
    }

    /** (Re)read {@code hud-bar-placements.json} into the placement fold's owner layer. */
    public static void reloadPlacements() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(PLACEMENTS_FILE), HudBarPlacementAsset.class,
                HudBarPlacementAsset.CODEC, HudBarPlacementConfig.getInstance(), "placement");
    }
}
