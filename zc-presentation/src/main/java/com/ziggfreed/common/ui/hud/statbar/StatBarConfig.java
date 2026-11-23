package com.ziggfreed.common.ui.hud.statbar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The stat bars in force, folded {@code defaults < pack < owner} by bar id without regard to case: the files under
 * {@code Server/ZiggfreedCommon/StatBars/}, then the owner's {@code mods/ziggfreedcommon/stat-bars.json}. The ordered
 * list every paint reads is built once after a layer moves, dropped from the merge methods themselves so a re-import
 * or an owner reload is read on the next paint.
 */
public final class StatBarConfig extends AbstractKeyedAssetConfig<StatBarAsset> {

    private static final StatBarConfig INSTANCE = new StatBarConfig();

    private volatile List<StatBarAsset> ordered;

    @Nonnull
    public static StatBarConfig getInstance() {
        return INSTANCE;
    }

    private StatBarConfig() {
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, StatBarAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        ordered = null;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, StatBarAsset> layer) {
        super.mergePackLayer(layer);
        ordered = null;
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, StatBarAsset> layer) {
        super.mergeOwnerLayer(layer);
        ordered = null;
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, StatBarAsset> layer,
            @Nonnull Map<String, String> gatedOut) {
        super.mergeOwnerLayer(layer, gatedOut);
        ordered = null;
    }

    /**
     * Every bar switched on, nearest its place first ({@code Order}, then id), the order a paint fills its slots. Built
     * on the first read after a layer moved; the list is shared and never changed.
     */
    @Nonnull
    public List<StatBarAsset> enabledInOrder() {
        List<StatBarAsset> current = ordered;
        if (current == null) {
            List<StatBarAsset> out = new ArrayList<>();
            for (StatBarAsset bar : all().values()) {
                if (bar != null && bar.enabled()) {
                    out.add(bar);
                }
            }
            out.sort(Comparator.comparingInt(StatBarAsset::order)
                    .thenComparing(bar -> bar.getId() == null ? "" : bar.getId(), String.CASE_INSENSITIVE_ORDER));
            current = List.copyOf(out);
            ordered = current;
        }
        return current;
    }
}
