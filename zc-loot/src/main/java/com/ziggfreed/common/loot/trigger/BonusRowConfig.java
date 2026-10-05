package com.ziggfreed.common.loot.trigger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.util.SafeLog;

/**
 * The {@code defaults < pack < owner} fold of every {@link BonusRowAsset}, keyed by lower-cased id,
 * and the {@link BonusTable} it folds to, rebuilt on the first read after any layer merges.
 *
 * <p>Rows fold in sorted id order, so two rows claiming one pattern in one moment resolve to the id
 * that sorts last, whatever order the packs loaded in. A switched-off row is left out; a row naming
 * no moment is left out and recorded for the audit.
 */
public final class BonusRowConfig extends AbstractKeyedAssetConfig<BonusRowAsset> {

    private static final BonusRowConfig INSTANCE = new BonusRowConfig();

    /** The table and the momentless rows one fold produced. */
    private record Folded(@Nonnull BonusTable<BonusRow> table, @Nonnull Map<String, String> momentless) {
    }

    @Nullable private volatile Folded folded;

    private final BonusRolls rolls = new BonusRolls(SafeLog::warn);

    private BonusRowConfig() {
    }

    @Nonnull
    public static BonusRowConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, BonusRowAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        invalidate();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, BonusRowAsset> layer) {
        super.mergePackLayer(layer);
        invalidate();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, BonusRowAsset> layer) {
        super.mergeOwnerLayer(layer);
        invalidate();
    }

    /** The folded table every library row answers through. */
    @Nonnull
    public BonusTable<BonusRow> table() {
        return folded().table();
    }

    /** The row covering {@code name} in {@code moment}, or null when none does. */
    @Nullable
    public BonusRow bestFor(@Nonnull BonusMoment moment, @Nullable String name) {
        return table().bestFor(moment, name);
    }

    /** Ids of rows switched on but naming no moment, paired with what they wrote there. */
    @Nonnull
    public Map<String, String> momentlessRows() {
        return folded().momentless();
    }

    /** The warn-once roll resolution every library row reads through; re-armed on every merge. */
    @Nonnull
    public BonusRolls rolls() {
        return rolls;
    }

    private void invalidate() {
        folded = null;
        rolls.reset();
    }

    @Nonnull
    private Folded folded() {
        Folded local = folded;
        if (local == null) {
            synchronized (this) {
                local = folded;
                if (local == null) {
                    local = fold();
                    folded = local;
                }
            }
        }
        return local;
    }

    @Nonnull
    private Folded fold() {
        Map<String, BonusRowAsset> all = all();
        List<String> ids = new ArrayList<>(all.keySet());
        Collections.sort(ids);
        List<BonusTable.Row<BonusRow>> rows = new ArrayList<>();
        Map<String, String> momentless = new LinkedHashMap<>();
        for (String id : ids) {
            BonusRowAsset asset = all.get(id);
            if (!asset.isEnabled()) {
                continue;
            }
            BonusMoment moment = asset.moment();
            if (moment == null) {
                momentless.put(id, asset.getWhen() == null ? "" : asset.getWhen().rawKind());
                continue;
            }
            LootRef loot = asset.getLoot();
            BonusRow row = new BonusRow(moment, asset.match(), id, loot == null ? LootRef.of(null, null) : loot,
                    asset.getChance());
            rows.add(BonusTable.Row.of(asset.match(), row));
        }
        return new Folded(BonusTable.fold(rows), Collections.unmodifiableMap(momentless));
    }
}
