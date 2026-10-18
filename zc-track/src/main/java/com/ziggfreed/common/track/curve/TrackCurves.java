package com.ziggfreed.common.track.curve;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.gate.GateEpochs;
import com.ziggfreed.common.track.asset.TrackCurveAsset;
import com.ziggfreed.common.track.asset.TrackCurveConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every curve tabulated once, by id: the table a level read uses, built on the first ask after the curves
 * last loaded and kept until they load again. {@link #generation()} counts those reloads, so a memo of a
 * value derived from a level (a compiled gain plan, a power reading) folds it into its epoch and lapses when
 * a curve moves, with no callback.
 */
public final class TrackCurves {

    private static final Map<String, CurveTable> TABLES = new ConcurrentHashMap<>();

    private static final AtomicInteger GENERATION = new AtomicInteger();

    private TrackCurves() {
    }

    /** The table of the curve {@code curveId} (any case), or null when no folded curve has that id. */
    @Nullable
    public static CurveTable table(@Nullable String curveId) {
        if (curveId == null || curveId.isBlank()) {
            return null;
        }
        String key = curveId.trim().toLowerCase(Locale.ROOT);
        CurveTable held = TABLES.get(key);
        if (held != null) {
            return held;
        }
        int generation = GENERATION.get();
        TrackCurveAsset asset = TrackCurveConfig.getInstance().resolve(key);
        if (asset == null) {
            return null;
        }
        try {
            CurveTable built = asset.table();
            // A table built across a reload is answered once and never kept.
            if (generation == GENERATION.get()) {
                TABLES.put(key, built);
            }
            return built;
        } catch (Throwable t) {
            SafeLog.warn("[track] the curve '" + curveId + "' could not be tabulated: " + t.getMessage());
            return null;
        }
    }

    /** How many times the curves have reloaded since start. Only ever grows. */
    public static int generation() {
        return GENERATION.get();
    }

    /**
     * The curves changed: drop every table, move the generation, and move the gate server epoch, since a level
     * every player's track readings answer may have moved with them. Called by every curve fold.
     */
    public static void invalidate() {
        TABLES.clear();
        GENERATION.incrementAndGet();
        GateEpochs.bumpServer();
    }
}
