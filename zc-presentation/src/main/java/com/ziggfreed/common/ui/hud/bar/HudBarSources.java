package com.ziggfreed.common.ui.hud.bar;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.util.SafeLog;

/**
 * The registry of {@link HudBarSource}s, one per namespace: the seam through which the panel asks a
 * mod it has never heard of what one of that mod's values reads right now, to draw the row's fill.
 *
 * <p>A consumer registers once from its own setup, under the namespace its value ids start with; the
 * panel splits a row's id at its colon, looks the namespace up here and hands the source the rest.
 * A row moved under a namespace nothing answers for is reported once per namespace and then drawn
 * without a fill, because a panel that stays quiet about an unfilled seam would be a bar that
 * silently never fills.
 */
public final class HudBarSources {

    /** The separator between the namespace and the local part of a value id. */
    static final char SOURCE_SEPARATOR = ':';

    private static final Map<String, HudBarSource> SOURCES = new ConcurrentHashMap<>();

    /** Namespaces already reported as unfilled, so the log says it once rather than per paint. */
    private static final Set<String> REPORTED_UNFILLED = ConcurrentHashMap.newKeySet();

    private HudBarSources() {
    }

    /**
     * Answer for every row whose id starts with {@code namespace} (case-insensitive; folded lower).
     * A second registration under the same namespace replaces the first.
     */
    public static void register(@Nonnull String namespace, @Nonnull HudBarSource source) {
        SOURCES.put(fold(namespace), source);
        REPORTED_UNFILLED.remove(fold(namespace));
    }

    /** Forget the source registered under {@code namespace}; for a consumer shutting down or a test. */
    public static void unregister(@Nonnull String namespace) {
        SOURCES.remove(fold(namespace));
    }

    /** The source registered under {@code namespace}, or null. */
    @Nullable
    public static HudBarSource resolve(@Nullable String namespace) {
        return namespace == null ? null : SOURCES.get(fold(namespace));
    }

    /** Whether anything answers for {@code namespace}. */
    public static boolean isFilled(@Nullable String namespace) {
        return resolve(namespace) != null;
    }

    /**
     * The reading behind the value {@code sourceId} for {@code playerRef}, or null when the id has no
     * namespace, nothing answers for its namespace, or the source declined. Guarded: a source that
     * throws costs its own row's fill for this paint and one line at fine, never the panel.
     */
    @Nullable
    public static HudBarSource.Reading read(@Nonnull PlayerRef playerRef, @Nonnull String sourceId) {
        String namespace = namespaceOf(sourceId);
        String localId = localIdOf(sourceId);
        if (namespace == null || localId == null) {
            return null;
        }
        HudBarSource source = SOURCES.get(namespace);
        if (source == null) {
            if (REPORTED_UNFILLED.add(namespace)) {
                SafeLog.warn("[hud] a row moved under '" + sourceId + "' reads its fill from '" + namespace
                        + "', and no mod has registered a source under that name, so it draws no fill");
            }
            return null;
        }
        try {
            return source.read(playerRef, localId);
        } catch (Throwable t) {
            SafeLog.fine("[hud] the '" + namespace + "' bar source failed to read '" + localId + "': "
                    + t.getMessage());
            return null;
        }
    }

    // ==================== a value id's two halves ====================

    /**
     * The mod a value belongs to: the part of a {@code namespace:local} id before the colon,
     * lower-cased so a registration and a reported id can never disagree by case. Null when the id
     * is null or carries no namespace.
     */
    @Nullable
    public static String namespaceOf(@Nullable String sourceId) {
        if (sourceId == null) {
            return null;
        }
        int at = sourceId.indexOf(SOURCE_SEPARATOR);
        if (at <= 0) {
            return null;
        }
        return sourceId.substring(0, at).trim().toLowerCase(Locale.ROOT);
    }

    /** The owning mod's own name for the value: the part after the colon, or null when there is no namespace or nothing after it. */
    @Nullable
    public static String localIdOf(@Nullable String sourceId) {
        if (sourceId == null) {
            return null;
        }
        int at = sourceId.indexOf(SOURCE_SEPARATOR);
        if (at <= 0 || at == sourceId.length() - 1) {
            return null;
        }
        String local = sourceId.substring(at + 1).trim();
        return local.isEmpty() ? null : local;
    }

    /** Drop every registration and every report; for a test that installs its own. */
    static void clearForTests() {
        SOURCES.clear();
        REPORTED_UNFILLED.clear();
    }

    @Nonnull
    private static String fold(@Nonnull String namespace) {
        return namespace.trim().toLowerCase(Locale.ROOT);
    }
}
