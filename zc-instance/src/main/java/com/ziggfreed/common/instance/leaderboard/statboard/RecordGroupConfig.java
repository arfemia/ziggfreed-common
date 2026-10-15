package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The groups a Records split sorts its things into ({@link RecordGroupAsset}), folded {@code defaults < pack < owner}
 * like every keyed asset type and resolved once per change into the groups in segment order.
 */
public final class RecordGroupConfig extends AbstractKeyedAssetConfig<RecordGroupAsset> {

    private static final RecordGroupConfig INSTANCE = new RecordGroupConfig();

    private static final Comparator<RecordGroup> SEGMENT_ORDER =
            Comparator.comparingInt(RecordGroup::order).thenComparing(RecordGroup::id);

    private volatile List<RecordGroup> groups = List.of();

    @Nonnull
    public static RecordGroupConfig getInstance() {
        return INSTANCE;
    }

    private RecordGroupConfig() {
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, RecordGroupAsset> layer) {
        super.mergePackLayer(layer);
        rebuild();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, RecordGroupAsset> layer) {
        super.mergeOwnerLayer(layer);
        rebuild();
    }

    /** Every group, in segment order. */
    @Nonnull
    public List<RecordGroup> groups() {
        return groups;
    }

    /** The group {@code id} names, matched without regard to case, or null when no file defines it. */
    @Nullable
    public RecordGroup group(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        RecordGroupAsset asset = resolve(id.trim());
        return asset == null ? null : asset.toGroup(id.trim());
    }

    private void rebuild() {
        List<RecordGroup> out = new ArrayList<>();
        for (Map.Entry<String, RecordGroupAsset> e : all().entrySet()) {
            out.add(e.getValue().toGroup(e.getKey()));
        }
        out.sort(SEGMENT_ORDER);
        groups = List.copyOf(out);
    }
}
