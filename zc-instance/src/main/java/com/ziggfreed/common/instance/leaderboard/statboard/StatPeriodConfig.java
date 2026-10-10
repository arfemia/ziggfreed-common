package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * The stat periods in force, folded {@code defaults < pack < owner} like every keyed asset type, and
 * resolved once per fold into the {@link StatPeriod}s the board runs, tab order first. A file that cannot
 * run is left out and named once in the log, at the fold that brought it.
 */
public final class StatPeriodConfig extends AbstractKeyedAssetConfig<StatPeriodAsset> {

    private static final StatPeriodConfig INSTANCE = new StatPeriodConfig();

    private static final Comparator<StatPeriod> TAB_ORDER =
            Comparator.comparingInt(StatPeriod::order).thenComparing(StatPeriod::id);

    private volatile List<StatPeriod> periods = List.of();

    /** Each problem already named in the log, so a refold (the owner file's two-step read) names it once. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    @Nonnull
    public static StatPeriodConfig getInstance() {
        return INSTANCE;
    }

    private StatPeriodConfig() {
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, StatPeriodAsset> layer) {
        super.mergePackLayer(layer);
        rebuild();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, StatPeriodAsset> layer) {
        super.mergeOwnerLayer(layer);
        rebuild();
    }

    /** Every period that can run, in tab order. */
    @Nonnull
    public List<StatPeriod> periods() {
        return periods;
    }

    private void rebuild() {
        List<StatPeriod> out = new ArrayList<>();
        for (Map.Entry<String, StatPeriodAsset> e : all().entrySet()) {
            String problem = e.getValue().problem();
            if (problem != null) {
                if (warned.add(e.getKey() + ": " + problem)) {
                    SafeLog.warn("[statboard] the stat period '" + e.getKey() + "' shows no tab and counts "
                            + "nothing: " + problem);
                }
                continue;
            }
            StatPeriod period = e.getValue().toPeriod(e.getKey());
            if (period != null) {
                out.add(period);
            }
        }
        out.sort(TAB_ORDER);
        periods = List.copyOf(out);
    }
}
