package com.ziggfreed.common.ui.hud.bar;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link HudBarAsset}, keyed by bar id.
 *
 * <p>Process-wide, because the defining ASSETS are: one folder, one fold, one answer to "which bar
 * does this value fill", however many mods author bars. The library ships no bars of its own; every
 * entry is a consumer's pack JSON, and a server owner's {@code mods/ziggfreedcommon/hud-bars.json}
 * overlays leaf by leaf ({@link HudBarOwnerLayers}).
 *
 * <p>{@link #bySource} is the read the panel makes on every value change, so the source index is
 * kept beside the fold and rebuilt whenever a layer merges rather than scanned per change.
 */
public final class HudBarConfig extends AbstractKeyedAssetConfig<HudBarAsset> {

    private static final HudBarConfig INSTANCE = new HudBarConfig();

    /** Source id (as authored, trimmed) to the bar it fills; rebuilt lazily after a merge. */
    @Nullable private volatile Map<String, HudBarAsset> bySource;

    private HudBarConfig() {
    }

    @Nonnull
    public static HudBarConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, HudBarAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        bySource = null;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, HudBarAsset> layer) {
        super.mergePackLayer(layer);
        bySource = null;
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, HudBarAsset> layer) {
        super.mergeOwnerLayer(layer);
        bySource = null;
    }

    /**
     * The enabled bar {@code sourceId} fills, or null when no authored bar names that source (or the
     * one that does is switched off). Two bars naming one source resolve to the one that sorts first
     * by {@link HudBarAsset#order()} then id, so the answer is stable across reloads.
     */
    @Nullable
    public HudBarAsset bySource(@Nullable String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return null;
        }
        return index().get(sourceId.trim().toLowerCase(Locale.ROOT));
    }

    @Nonnull
    private Map<String, HudBarAsset> index() {
        Map<String, HudBarAsset> snapshot = bySource;
        if (snapshot != null) {
            return snapshot;
        }
        Map<String, HudBarAsset> built = new LinkedHashMap<>();
        all().values().stream()
                .filter(bar -> bar.enabled() && bar.source() != null)
                .sorted(HudBarConfig::byOrderThenId)
                .forEach(bar -> built.putIfAbsent(bar.source().toLowerCase(Locale.ROOT), bar));
        bySource = built;
        return built;
    }

    /** The one ordering every listing of bars uses: authored Order, then id. */
    static int byOrderThenId(@Nonnull HudBarAsset a, @Nonnull HudBarAsset b) {
        int byOrder = Integer.compare(a.order(), b.order());
        if (byOrder != 0) {
            return byOrder;
        }
        String idA = a.getId() == null ? "" : a.getId();
        String idB = b.getId() == null ? "" : b.getId();
        return idA.compareTo(idB);
    }
}
