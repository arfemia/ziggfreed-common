package com.ziggfreed.common.ui.hud.panel;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link HudRowAsset}, keyed by override id.
 *
 * <p>Process-wide, because the defining ASSETS are: one folder, one fold, one answer to "has
 * anyone said anything about this row", however many mods author overrides. The library ships
 * none of its own; every entry is a pack's JSON or a server owner's
 * {@code mods/ziggfreedcommon/hud-rows.json} entry ({@link HudOwnerLayers}), which overlays a
 * pack's same-id file leaf by leaf or stands on its own when no pack authored that id.
 *
 * <p>{@link #bySource} is the read the panel makes on every move and every paint, so the index by
 * row id is kept beside the fold and rebuilt whenever a layer merges rather than scanned per read.
 */
public final class HudRowConfig extends AbstractKeyedAssetConfig<HudRowAsset> {

    private static final HudRowConfig INSTANCE = new HudRowConfig();

    /** Row id (folded lower) to the override authored for it; rebuilt lazily after a merge. */
    @Nullable private volatile Map<String, HudRowAsset> bySource;

    private HudRowConfig() {
    }

    @Nonnull
    public static HudRowConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, HudRowAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        bySource = null;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, HudRowAsset> layer) {
        super.mergePackLayer(layer);
        bySource = null;
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, HudRowAsset> layer) {
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
    public HudRowAsset bySource(@Nullable String rowId) {
        if (rowId == null || rowId.isBlank()) {
            return null;
        }
        return index().get(rowId.trim().toLowerCase(Locale.ROOT));
    }

    @Nonnull
    private Map<String, HudRowAsset> index() {
        Map<String, HudRowAsset> snapshot = bySource;
        if (snapshot != null) {
            return snapshot;
        }
        Map<String, HudRowAsset> built = new LinkedHashMap<>();
        all().values().stream()
                .filter(bar -> bar.source() != null)
                .sorted(HudRowConfig::byOrderThenId)
                .forEach(bar -> built.putIfAbsent(bar.source().toLowerCase(Locale.ROOT), bar));
        bySource = built;
        return built;
    }

    /** The one ordering two overrides of one row are ranked by: authored Order (unauthored last), then id. */
    static int byOrderThenId(@Nonnull HudRowAsset a, @Nonnull HudRowAsset b) {
        int byOrder = Integer.compare(orderOf(a), orderOf(b));
        if (byOrder != 0) {
            return byOrder;
        }
        String idA = a.getId() == null ? "" : a.getId();
        String idB = b.getId() == null ? "" : b.getId();
        return idA.compareTo(idB);
    }

    private static int orderOf(@Nonnull HudRowAsset bar) {
        Integer order = bar.order();
        return order != null ? order : HudRowLook.DEFAULT_ORDER;
    }
}
