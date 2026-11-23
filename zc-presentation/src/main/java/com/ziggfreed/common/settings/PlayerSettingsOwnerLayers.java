package com.ziggfreed.common.settings;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;

import com.google.gson.JsonObject;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.JsonOverrideWriter;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The SERVER OWNER's last word on players' defaults and locks, at
 * {@code mods/ziggfreedcommon/player-settings.json}: a bare map from a record id to the leaves that
 * record should read differently, decoded against the packs' own answer through the same codec, exactly
 * like every other owner file this library reads.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/player-settings.json
 * { "Default": { "QuestTracker": { "Show": { "Default": false } } } }
 * }</pre>
 *
 * <p>Read from the store's own load event, since an owner entry has nothing to inherit from until the
 * packs have landed. Every read is told to whoever {@linkplain #onReload listens} (the quest tracker
 * re-anchors on it), and the quest tracker's owner leaves are written here, in the shape an owner would type
 * them ({@link #setTrackerLeaves}).
 */
public final class PlayerSettingsOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file over the record. */
    public static final String FILE = "player-settings.json";

    /** The record id an owner writes under, as the shipped file is named. */
    public static final String ENTRY = "Default";

    /** The group the quest tracker's leaves sit in. */
    public static final String TRACKER_GROUP = "QuestTracker";

    private static final String LOG_TAG = "settings";

    private static final List<Runnable> RELOAD_LISTENERS = new CopyOnWriteArrayList<>();

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private PlayerSettingsOwnerLayers() {
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
     * Be told after every read of the file, to redraw what reads the owner's word (the quest tracker's switch and
     * place). Any thread; keep it short.
     */
    public static void onReload(@Nonnull Runnable listener) {
        RELOAD_LISTENERS.add(listener);
    }

    /** (Re)read {@code player-settings.json} into the record fold's owner layer, then tell every listener. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, file(), PlayerSettingsAsset.class,
                PlayerSettingsAsset.CODEC, PlayerSettingsConfig.getInstance(), "player-settings record");
        for (Runnable listener : RELOAD_LISTENERS) {
            try {
                listener.run();
            } catch (Throwable t) {
                SafeLog.warn("[" + LOG_TAG + "] a player-settings listener failed: " + t.getMessage());
            }
        }
    }

    /**
     * Write {@code leaves} (dotted paths inside the quest tracker's group, {@code Enabled} or {@code Position.OffsetX};
     * a null value removes that leaf) under the record's entry, spelt as the file already spells it, every other key
     * kept; then read the file again. True when written. The one writer of the tracker's owner leaves: the HUD layout
     * page and a consumer's older command both come through here.
     */
    public static boolean setTrackerLeaves(@Nonnull Map<String, Object> leaves) {
        if (leaves.isEmpty()) {
            return true;
        }
        Path file = file();
        String prefix = entryKey(file) + "." + TRACKER_GROUP + ".";
        Map<String, Object> absolute = new LinkedHashMap<>();
        for (Map.Entry<String, Object> leaf : leaves.entrySet()) {
            absolute.put(prefix + leaf.getKey(), leaf.getValue());
        }
        boolean written = JsonOverrideWriter.setLeaves(file, absolute);
        if (written) {
            reload();
        }
        return written;
    }

    /** The key the record is already filed under in {@code file}, in its own spelling, else {@value #ENTRY}. */
    @Nonnull
    static String entryKey(@Nonnull Path file) {
        JsonObject root = OwnerLayerReader.readObject(LOG_TAG, file);
        if (root != null) {
            for (String existing : root.keySet()) {
                if (!OwnerFiles.isReservedKey(existing) && existing.trim().equalsIgnoreCase(ENTRY)) {
                    return existing;
                }
            }
        }
        return ENTRY;
    }
}
