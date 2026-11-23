package com.ziggfreed.common.objectives.settings;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The SERVER OWNER's word on the shared quest engine, at {@code mods/ziggfreedcommon/progression.json}: a bare map from
 * a record id to the leaves that record should read differently, decoded against the packs' own answer through the same
 * codec, exactly like every other owner file this library reads.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/progression.json
 * { "Default": { "Quests": { "MaxActive": 10 } } }
 * }</pre>
 *
 * <p>Read from the store's own load event, since an owner entry has nothing to inherit from until the packs have
 * landed. The quest settings form ({@code OwnerForms/Quest_Settings.json}) edits it through {@link #BINDING}.
 */
public final class ProgressionSettingsOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file over the record. */
    public static final String FILE = "progression.json";

    /** The file's id as an owner form names it (zc-core's {@code OwnerFileBindings}): its path under {@code mods/}. */
    public static final String BINDING = "ziggfreedcommon/" + FILE;

    /** The record id an owner writes under, as the shipped file is named. */
    public static final String ENTRY = "Default";

    private static final String LOG_TAG = "progression";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private ProgressionSettingsOwnerLayers() {
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

    /** The owner file itself. */
    @Nonnull
    public static Path file() {
        return directory.resolve(FILE);
    }

    /**
     * (Re)read {@code progression.json} into the record fold's owner layer, then say in one line the quest limit now in
     * force (the owner's word over the packs' record), so an owner reading the log sees which limit the server runs.
     */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, file(), ProgressionSettingsAsset.class,
                ProgressionSettingsAsset.CODEC, ProgressionSettingsConfig.getInstance(), "progression settings");
        int limit = ProgressionSettingsConfig.getInstance().maxActiveQuests();
        SafeLog.info("[" + LOG_TAG + "] quest log limit in force: "
                + (limit > 0 ? limit + " quest(s) at once" : "none") + " (Quests.MaxActive, " + FILE
                + " over the packs' Default record)");
    }
}
