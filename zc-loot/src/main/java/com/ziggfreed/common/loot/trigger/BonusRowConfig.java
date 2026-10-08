package com.ziggfreed.common.loot.trigger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.util.SafeLog;

/**
 * The {@code defaults < pack < owner} fold of every {@link BonusRowAsset}, keyed by lower-cased id,
 * and the {@link BonusTable} it folds to, rebuilt on the first read after any layer merges.
 *
 * <p>Rows fold in sorted id order, so two rows claiming one pattern in one moment resolve to the id
 * that sorts last, whatever order the packs loaded in. A switched-off row is left out; a row out of
 * its {@code Season} is left out the same way, and the table folds again when a season it names turns
 * over; a row naming no moment is left out and recorded for the audit.
 */
public final class BonusRowConfig extends AbstractKeyedAssetConfig<BonusRowAsset> {

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "BonusRows";

    private static final BonusRowConfig INSTANCE = new BonusRowConfig();

    /**
     * What one fold produced: the live table (rows out of season left out), every enabled row whatever
     * its season (the audit's view), the momentless rows, the season each seasonal row names, and the
     * seasons that were running when it folded.
     */
    private record Folded(@Nonnull BonusTable<BonusRow> table, @Nonnull BonusTable<BonusRow> everyRow,
            @Nonnull Map<String, String> momentless, @Nonnull Map<String, String> seasons,
            @Nonnull Set<String> liveSeasons) {
    }

    @Nullable private volatile Folded folded;

    private final BonusRolls rolls = new BonusRolls(SafeLog::warn);

    private BonusRowConfig() {
        super(MOD_GATE_STORE);
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

    /**
     * The folded table every library row answers through: rows out of their season are left out, as a
     * switched-off row is. When a season a row names has started or ended since the last fold, the
     * table is folded again (the warn-once roll state is kept; only a merge re-arms it).
     */
    @Nonnull
    public BonusTable<BonusRow> table() {
        Folded local = folded();
        if (!local.liveSeasons().equals(liveNow(local.seasons().values()))) {
            local = refold(local);
        }
        return local.table();
    }

    /** Every enabled row, whatever its season: what the audit reads, so an off-season row is still checked. */
    @Nonnull
    public BonusTable<BonusRow> authoredTable() {
        return folded().everyRow();
    }

    /** The season each enabled row names, keyed by row id; a row on all year is not in it. */
    @Nonnull
    public Map<String, String> seasonsByRow() {
        return folded().seasons();
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

    /** Fold again because a season turned over; another thread's newer fold is kept. */
    @Nonnull
    private synchronized Folded refold(@Nonnull Folded stale) {
        if (folded == stale) {
            folded = null;
        }
        return folded();
    }

    /** The seasons in {@code seasons} running right now, lower-cased. */
    @Nonnull
    private static Set<String> liveNow(@Nonnull Collection<String> seasons) {
        Set<String> out = new TreeSet<>();
        for (String season : seasons) {
            if (SeasonGate.live(season)) {
                out.add(season.toLowerCase(Locale.ROOT));
            }
        }
        return out;
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
        List<BonusTable.Row<BonusRow>> live = new ArrayList<>();
        List<BonusTable.Row<BonusRow>> every = new ArrayList<>();
        Map<String, String> momentless = new LinkedHashMap<>();
        Map<String, String> seasons = new LinkedHashMap<>();
        // One reading per season per fold: the rows kept and the live set recorded come from the same
        // answer, so a run starting mid-fold cannot be recorded as seen with its rows left out.
        Map<String, Boolean> liveBySeason = new HashMap<>();
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
            String season = asset.getSeason();
            boolean inSeason = true;
            if (season != null) {
                seasons.put(id, season);
                inSeason = liveBySeason.computeIfAbsent(season.toLowerCase(Locale.ROOT),
                        key -> SeasonGate.live(season));
            }
            LootRef loot = asset.getLoot();
            BonusRow row = new BonusRow(moment, asset.match(), id, loot == null ? LootRef.of(null, null) : loot,
                    asset.getChance());
            BonusTable.Row<BonusRow> entry = BonusTable.Row.of(asset.match(), row);
            every.add(entry);
            if (inSeason) {
                live.add(entry);
            }
        }
        Set<String> liveSeasons = new TreeSet<>();
        liveBySeason.forEach((season, on) -> {
            if (on) {
                liveSeasons.add(season);
            }
        });
        return new Folded(BonusTable.fold(live), BonusTable.fold(every), Collections.unmodifiableMap(momentless),
                Collections.unmodifiableMap(seasons), liveSeasons);
    }
}
