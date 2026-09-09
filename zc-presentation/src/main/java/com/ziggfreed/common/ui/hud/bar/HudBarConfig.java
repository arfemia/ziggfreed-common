package com.ziggfreed.common.ui.hud.bar;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link HudBarAsset}, keyed by override id.
 *
 * <p>Process-wide, because the defining ASSETS are: one folder, one fold, one answer to "has
 * anyone said anything about this row", however many mods author overrides. The library ships
 * none of its own; every entry is a pack's JSON or a server owner's
 * {@code mods/ziggfreedcommon/hud-bars.json} entry ({@link HudBarOwnerLayers}), which overlays a
 * pack's same-id file leaf by leaf or stands on its own when no pack authored that id.
 *
 * <p>{@link #bySource} is the read the panel makes on every move and every paint, so the index by
 * row id is kept beside the fold and rebuilt whenever a layer merges rather than scanned per read.
 */
public final class HudBarConfig extends AbstractKeyedAssetConfig<HudBarAsset> {

    private static final HudBarConfig INSTANCE = new HudBarConfig();

    /** Row id (folded lower) to the override authored for it; rebuilt lazily after a merge. */
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
     * The override authored for the row moved under {@code rowId} (matched whole, ignoring case,
     * against each file's {@code Source}), switched off or not, or null when nothing is authored
     * for it (the common case: the row then reads exactly what the reporting mod said). A disabled
     * override is answered so the panel can honour the switch. Two files naming one row resolve to
     * the one that sorts first by {@code Order} then id, so the answer is stable across reloads.
     */
    @Nullable
    public HudBarAsset bySource(@Nullable String rowId) {
        if (rowId == null || rowId.isBlank()) {
            return null;
        }
        return index().get(rowId.trim().toLowerCase(Locale.ROOT));
    }

    @Nonnull
    private Map<String, HudBarAsset> index() {
        Map<String, HudBarAsset> snapshot = bySource;
        if (snapshot != null) {
            return snapshot;
        }
        Map<String, HudBarAsset> built = new LinkedHashMap<>();
        all().values().stream()
                .filter(bar -> bar.source() != null)
                .sorted(HudBarConfig::byOrderThenId)
                .forEach(bar -> built.putIfAbsent(bar.source().toLowerCase(Locale.ROOT), bar));
        bySource = built;
        return built;
    }

    /** The one ordering two overrides of one row are ranked by: authored Order (unauthored last), then id. */
    static int byOrderThenId(@Nonnull HudBarAsset a, @Nonnull HudBarAsset b) {
        int byOrder = Integer.compare(orderOf(a), orderOf(b));
        if (byOrder != 0) {
            return byOrder;
        }
        String idA = a.getId() == null ? "" : a.getId();
        String idB = b.getId() == null ? "" : b.getId();
        return idA.compareTo(idB);
    }

    private static int orderOf(@Nonnull HudBarAsset bar) {
        Integer order = bar.order();
        return order != null ? order : HudBarLook.DEFAULT_ORDER;
    }
}
