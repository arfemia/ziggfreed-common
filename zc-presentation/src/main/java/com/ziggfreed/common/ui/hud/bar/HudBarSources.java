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
 * mod it has never heard of what one of that mod's values reads right now.
 *
 * <p>A consumer registers once from its own setup, under the namespace its bars' {@code Source} ids
 * start with; the panel splits an authored source id at its colon, looks the namespace up here and
 * hands the source the rest. A bar naming a namespace nothing answers for is reported once per
 * namespace and then drawn as nothing, because a panel that stays quiet about an unfilled seam
 * would be a bar that silently never comes up.
 */
public final class HudBarSources {

    private static final Map<String, HudBarSource> SOURCES = new ConcurrentHashMap<>();

    /** Namespaces already reported as unfilled, so the log says it once rather than per paint. */
    private static final Set<String> REPORTED_UNFILLED = ConcurrentHashMap.newKeySet();

    private HudBarSources() {
    }

    /**
     * Answer for every bar whose {@code Source} starts with {@code namespace} (case-insensitive;
     * folded lower). A second registration under the same namespace replaces the first.
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
     * The reading behind {@code bar} for {@code playerRef}, or null when its source names nothing,
     * nothing answers for its namespace, or the source declined. Guarded: a source that throws costs
     * its own bar for this paint and one line at fine, never the panel.
     */
    @Nullable
    public static HudBarSource.Reading read(@Nonnull PlayerRef playerRef, @Nonnull HudBarAsset bar) {
        String namespace = bar.sourceNamespace();
        String localId = bar.sourceLocalId();
        if (namespace == null || localId == null) {
            return null;
        }
        HudBarSource source = SOURCES.get(namespace);
        if (source == null) {
            if (REPORTED_UNFILLED.add(namespace)) {
                SafeLog.warn("[hud] the bar '" + bar.getId() + "' reads a value from '" + namespace
                        + "', and no mod has registered a source under that name, so it will not be drawn");
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
