package com.ziggfreed.common.ui.hud.panel;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;

/**
 * The SERVER OWNER's last word on the rows, the panels and the spots they can sit at, at
 * {@code mods/ziggfreedcommon/hud-rows.json}, {@code hud-panels.json} and
 * {@code hud-spots.json}: each a bare map from an id to the leaves that id should read
 * differently, decoded against the packs' own answer through the same codecs the files use, exactly
 * like every other owner file this library reads. An entry under an id no pack authored stands on
 * its own, so an owner retunes a row nobody shipped a file for by naming it in {@code Source}, or
 * adds a spot of their own by naming it here.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/hud-rows.json
 * { "wood_off":   { "Source": "WOOD", "Enabled": false },
 *   "slow_stone": { "Source": "STONE", "Color": "#c0c0c0", "LingerMs": 8000 } }
 *
 * // mods/ziggfreedcommon/hud-panels.json
 * { "World_Bars": { "Placement": "Bottom_Left" },
 *   "Activity_Ledger": { "Position": { "OffsetY": 260 }, "MaxVisible": 6 } }
 *
 * // mods/ziggfreedcommon/hud-spots.json
 * { "Bottom_Left": { "Position": { "OffsetY": 40 } } }
 * }</pre>
 *
 * <p>Read from each store's own load event, since an owner entry has nothing to inherit from until
 * the packs have landed; the HUD settings page writes the panels file and re-reads it the same way.
 */
public final class HudOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file over the rows. */
    public static final String ROWS_FILE = "hud-rows.json";

    /** The owner file over the panels. */
    public static final String PANELS_FILE = "hud-panels.json";

    /** The owner file over the spots. */
    public static final String SPOTS_FILE = "hud-spots.json";

    private static final String LOG_TAG = "hud";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private HudOwnerLayers() {
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

    /** (Re)read {@code hud-rows.json} into the row fold's owner layer. */
    public static void reloadRows() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(ROWS_FILE), HudRowAsset.class,
                HudRowAsset.CODEC, HudRowConfig.getInstance(), "row");
    }

    /** (Re)read {@code hud-panels.json} into the panel fold's owner layer. */
    public static void reloadPanels() {
        OwnerLayerReader.apply(LOG_TAG, panelsFile(), HudPanelAsset.class,
                HudPanelAsset.CODEC, HudPanelConfig.getInstance(), "panel");
    }

    /** (Re)read {@code hud-spots.json} into the spot fold's owner layer. */
    public static void reloadSpots() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(SPOTS_FILE), HudSpotAsset.class,
                HudSpotAsset.CODEC, HudSpotConfig.getInstance(), "spot");
    }
}
