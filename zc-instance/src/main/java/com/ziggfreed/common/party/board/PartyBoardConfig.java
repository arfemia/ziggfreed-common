package com.ziggfreed.common.party.board;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of {@link PartyBoardAsset}, keyed by record id. The library ships
 * {@code Default.json} in its own jar's asset pack, so it rides the pack layer; the owner layer, from
 * {@code mods/ziggfreedcommon/}{@value #OWNER_FILE}, wins over both. A server with no record at all reads every
 * default ({@link #settings}).
 */
public final class PartyBoardConfig extends AbstractKeyedAssetConfig<PartyBoardAsset> {

    /** The owner file over the settings. */
    public static final String OWNER_FILE = "party-board.json";

    private static final PartyBoardConfig INSTANCE = new PartyBoardConfig();

    /** What a server with no record reads: every leaf unauthored. */
    private static final PartyBoardAsset DEFAULTS = new PartyBoardAsset();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private PartyBoardConfig() {
    }

    @Nonnull
    public static PartyBoardConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** (Re)read the owner file into the owner layer. From the store's own load event, after the packs. */
    public static void reloadOwner() {
        OwnerLayerReader.apply("party", directory.resolve(OWNER_FILE), PartyBoardAsset.class, PartyBoardAsset.CODEC,
                INSTANCE, "party board settings");
    }

    /** The folded record every board reads, or the defaults when no layer authored one. */
    @Nonnull
    public PartyBoardAsset settings() {
        return resolveOrDefault(PartyBoardAsset.SHARED_ID, DEFAULTS);
    }
}
