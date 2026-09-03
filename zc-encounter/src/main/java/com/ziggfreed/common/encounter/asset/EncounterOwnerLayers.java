package com.ziggfreed.common.encounter.asset;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;

/**
 * The SERVER OWNER's last word on encounters, at {@code mods/ziggfreedcommon/encounters.json} and
 * {@code mods/ziggfreedcommon/encounter-participation.json}: a bare map from an id to the leaves
 * that id should read differently, decoded against the packs' own answer through the same codecs
 * the files use, exactly like the economy's owner files.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/encounters.json
 * {
 *   "zc_encounter_example": { "Enabled": false },
 *   "kweebecnightmare_warden": { "Scale": { "HealthPerMember": 0.5 } }
 * }
 * }</pre>
 *
 * <p>Read from each store's own load event (an owner entry has nothing to inherit from until the
 * packs have landed) and again by {@code /zigencounter reload}.
 */
public final class EncounterOwnerLayers {

    /** Where a server owner's encounter files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The log prefix every encounter owner-file line carries. */
    private static final String LOG_TAG = "encounter";

    /** The owner file over the binding rows. */
    public static final String BINDINGS_FILE = "encounters.json";

    /** The owner file over the participation rules. */
    public static final String PARTICIPATION_FILE = "encounter-participation.json";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private EncounterOwnerLayers() {
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

    /** (Re)read {@code encounters.json} into the binding fold's owner layer. */
    public static void reloadBindings() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(BINDINGS_FILE), EncounterBindingAsset.class,
                EncounterBindingAsset.CODEC, EncounterBindingConfig.getInstance(), "encounter");
    }

    /** (Re)read {@code encounter-participation.json} into the rule fold's owner layer. */
    public static void reloadParticipation() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(PARTICIPATION_FILE),
                EncounterParticipationAsset.class, EncounterParticipationAsset.CODEC,
                EncounterParticipationConfig.getInstance(), "participation rule");
    }

    /** Both files, for the reload verb. */
    public static void reloadAll() {
        reloadBindings();
        reloadParticipation();
    }
}
