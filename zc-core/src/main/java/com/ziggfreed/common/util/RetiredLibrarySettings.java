package com.ziggfreed.common.util;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nonnull;

/**
 * CommonLib's own retired settings: the one list of the keys its owner files in
 * {@code mods/ziggfreedcommon/} may still write that this release no longer reads, taken out once at
 * setup by {@link RetiredSettings} (the copy, the one log line per file, and why it happens only once
 * are there). A setting this library retires gets its line here; a consumer lists its own files in its
 * own list and never one of these, so a server running CommonLib alone is cleaned the same way.
 */
public final class RetiredLibrarySettings {

    /** The release these settings retired in, which names the copy each file leaves behind. */
    public static final String RELEASE = "3.0.0";

    /** Every retired setting, one line each. */
    public static final List<RetiredSettings.Entry> ENTRIES = List.of(
            // The reward chest's window chain is gone (3.0.0): every chest opens its own page. View held one
            // leaf, ItemsWindow, which changed nothing since 2.5.0.
            new RetiredSettings.Entry("reward-chests.json", "*.View",
                    "A reward chest always opens its own page with the player's whole share on it, so"
                            + " View.ItemsWindow no longer changes anything."));

    private RetiredLibrarySettings() {
    }

    /** Run the pass over the library's owner folder, at setup before any owner file is read. Never throws. */
    public static void run() {
        run(OwnerFiles.DIRECTORY);
    }

    static void run(@Nonnull Path ownerDir) {
        for (RetiredSettings.Outcome outcome : RetiredSettings.strip(ownerDir, RELEASE, ENTRIES)) {
            if (outcome.failed()) {
                SafeLog.warn("[settings] " + outcome.line());
            } else {
                SafeLog.info("[settings] " + outcome.line());
            }
        }
    }
}
