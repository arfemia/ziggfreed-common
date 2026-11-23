package com.ziggfreed.common.encounter.power;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of {@link PlayerPowerAsset}, keyed by record id. The library ships
 * {@code Default.json} in its own jar's asset pack, so it rides the pack layer; the owner layer, from
 * {@code mods/ziggfreedcommon/}{@value #OWNER_FILE}, wins over both, and the owner menu's Player Power screen
 * ({@code OwnerForms/Player_Power.json}) writes it through {@link #binding()}. A server with no record at all reads
 * every leaf unauthored ({@link #settings}): every player at the scale's floor.
 */
public final class PlayerPowerConfig extends AbstractKeyedAssetConfig<PlayerPowerAsset> {

    /** The owner file over the reading. */
    public static final String OWNER_FILE = "player-power.json";

    /** The owner file's id as an owner form names it: its path under {@code mods/}. */
    public static final String BINDING = "ziggfreedcommon/" + OWNER_FILE;

    private static final PlayerPowerConfig INSTANCE = new PlayerPowerConfig();

    /** What a server with no record reads: every leaf unauthored. */
    private static final PlayerPowerAsset DEFAULTS = new PlayerPowerAsset();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private PlayerPowerConfig() {
    }

    @Nonnull
    public static PlayerPowerConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** The owner file over the reading, where the owner form writes it. */
    @Nonnull
    public static Path ownerFile() {
        return directory.resolve(OWNER_FILE);
    }

    /** (Re)read the owner file into the owner layer. From the store's own load event, after the packs. */
    public static void reloadOwner() {
        OwnerLayerReader.apply("power", ownerFile(), PlayerPowerAsset.class, PlayerPowerAsset.CODEC, INSTANCE,
                "player power");
    }

    /** The owner file's binding: its Default read off this store under the owner's layer, its reload the owner read. */
    @Nonnull
    public static OwnerFileBinding binding() {
        return OwnerFileBinding.keyedStore(BINDING, PlayerPowerConfig::ownerFile, INSTANCE, PlayerPowerAsset.CODEC,
                PlayerPowerConfig::reloadOwner);
    }

    /** The folded record the library's reading uses, or every leaf unauthored when no layer authored one. */
    @Nonnull
    public PlayerPowerAsset settings() {
        return resolveOrDefault(PlayerPowerAsset.SHARED_ID, DEFAULTS);
    }
}
