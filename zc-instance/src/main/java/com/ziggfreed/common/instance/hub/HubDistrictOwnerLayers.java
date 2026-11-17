package com.ziggfreed.common.instance.hub;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's word on the hub districts, in {@code mods/ziggfreedcommon/hub-districts.json}: each top-level
 * entry is a district id and the leaves it should read differently, decoded against the packs' own answer through the
 * same codec, and a new id is a district of the owner's own.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/hub-districts.json
 * { "Zc_Hub_Concourse": { "Prefab": { "Floor": "Rock_Stone_Brick_Smooth" } },
 *   "MyPack_Guild_Hall": { "Lot": { "Id": "East" } },
 *   "MyPack_Market":     { "Enabled": false } }
 * }</pre>
 *
 * <p>A district the owner moves to another lot claims it before any pack's, so the move wins a clash. A
 * {@code $}-prefixed key is file-level and never a district. Read from the district store's own load event, since an
 * owner entry has nothing to inherit from until the packs have landed; an entry gated on a missing mod, or retuning a
 * district the gate kept out, is dropped. A copy already open keeps its layout until it closes.
 */
public final class HubDistrictOwnerLayers {

    /** The owner file the districts are read from. */
    public static final String FILE = "hub-districts.json";

    private static final String LOG_TAG = "hub";

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private HubDistrictOwnerLayers() {
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

    /** (Re)read the owner file into the district fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, file(), HubDistrictAsset.class, HubDistrictAsset.CODEC,
                HubDistrictConfig.getInstance(), "hub district", d -> GateSpec.missingMod(d.requires()));
    }
}
