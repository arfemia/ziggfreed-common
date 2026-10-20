package com.ziggfreed.common.proc;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's last word on procs, counters and held effects, each a bare map from an id to the leaves that
 * file should read differently, decoded against the packs' own answer through the file's own codec:
 * {@code mods/ziggfreedcommon/}{@value #PROCS_FILE}, {@value #COUNTERS_FILE} and {@value #HELD_EFFECTS_FILE}.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/procs.json
 * { "Mmo_Defense_Cleanse_Step": { "Cooldown": { "Seconds": { "Base": 15 } } },
 *   "Mmo_Archery_Called_Shot": { "Enabled": false } }
 * }</pre>
 *
 * <p>An id no pack authored stands on its own as a new file. Each is read from its store's own load event. Every entry
 * follows the mod gate as its file does: one gated on a missing mod, or retuning a file the gate kept out, is dropped.
 */
public final class ProcOwnerLayers {

    /** The owner file over the procs. */
    public static final String PROCS_FILE = "procs.json";

    /** The owner file over the counters. */
    public static final String COUNTERS_FILE = "counters.json";

    /** The owner file over the held effects. */
    public static final String HELD_EFFECTS_FILE = "held-effects.json";

    private static final String LOG_TAG = "proc";

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private ProcOwnerLayers() {
    }

    /** Point the owner files at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** (Re)read {@value #PROCS_FILE} into the proc fold's owner layer. */
    public static void reloadProcs() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(PROCS_FILE), ProcAsset.class, ProcAsset.CODEC,
                ProcConfig.getInstance(), "proc", p -> GateSpec.missingMod(p.requires()));
    }

    /** (Re)read {@value #COUNTERS_FILE} into the counter fold's owner layer. */
    public static void reloadCounters() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(COUNTERS_FILE), CounterAsset.class, CounterAsset.CODEC,
                CounterConfig.getInstance(), "counter", c -> GateSpec.missingMod(c.requires()));
    }

    /** (Re)read {@value #HELD_EFFECTS_FILE} into the held-effect fold's owner layer. */
    public static void reloadHeldEffects() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(HELD_EFFECTS_FILE), HeldEffectAsset.class,
                HeldEffectAsset.CODEC, HeldEffectConfig.getInstance(), "held effect",
                h -> GateSpec.missingMod(h.requires()));
    }
}
