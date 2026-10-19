package com.ziggfreed.common.track.boost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.effect.buff.TimedBuffs;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.track.gain.GainPlans;

/**
 * The {@code defaults < pack < owner} fold of every {@link BoostAsset}. A file may gate on a mod through its
 * top-level {@code Requires} (the mod gate): the store's fold and its owner reader count what they leave out under
 * {@link #MOD_GATE_STORE}. Every merge builds the standing boosts' list again and what a calendar change can show or
 * hide on the buff bar ({@link #tilesFollow}), moves every compiled gain plan (a
 * boost's percent, tracks or switch may have changed) and re-syncs every online player's tiles (a running boost
 * may have been switched off).
 */
public final class BoostConfig extends AbstractKeyedAssetConfig<BoostAsset> {

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "Boosts";

    private static final BoostConfig INSTANCE = new BoostConfig();

    /** The switched-on standing boosts, in id order; rebuilt on every fold, read lock-free. */
    @Nonnull
    private volatile List<BoostAsset> standing = List.of();

    /** What a calendar change can move on the buff bar; rebuilt on every fold, read lock-free. */
    @Nonnull
    private volatile CalendarTiles calendarTiles = CalendarTiles.NONE;

    /**
     * The switched-on timed boosts a calendar change can show or hide (standing ones show no tile): the Seasons
     * they name, lower-cased, and whether any asks a {@code Requires} of the player, which may read the calendar.
     */
    private record CalendarTiles(@Nonnull Set<String> seasons, boolean requires) {

        static final CalendarTiles NONE = new CalendarTiles(Set.of(), false);
    }

    private BoostConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static BoostConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, BoostAsset> layer) {
        super.mergePackLayer(layer);
        folded();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, BoostAsset> layer) {
        super.mergeOwnerLayer(layer);
        folded();
    }

    /**
     * The boost {@code boostId} names (any case) when it is switched on, else null. Its season is not read here:
     * a boost out of season is hidden, which {@link BoostAsset#isPresent} answers.
     */
    @Nullable
    public BoostAsset live(@Nullable String boostId) {
        if (boostId == null || boostId.isBlank()) {
            return null;
        }
        BoostAsset boost = resolve(boostId.trim());
        return boost != null && boost.isEnabled() ? boost : null;
    }

    /** Every switched-on standing boost, in id order (each still reads its own season and Requires). */
    @Nonnull
    public List<BoostAsset> standing() {
        return standing;
    }

    /**
     * Can a change to any of the calendar events {@code eventIds} (any case) show or hide a boost tile: does a
     * switched-on timed boost name one of them as its {@code Season}, or ask a {@code Requires} of the player? False
     * at once when no timed boost carries either. Any thread.
     */
    public boolean tilesFollow(@Nonnull Collection<String> eventIds) {
        CalendarTiles held = calendarTiles;
        if (held.requires()) {
            return !eventIds.isEmpty();
        }
        if (held.seasons().isEmpty()) {
            return false;
        }
        for (String eventId : eventIds) {
            if (eventId != null && held.seasons().contains(eventId.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void folded() {
        List<BoostAsset> kept = new ArrayList<>();
        List<String> widened = new ArrayList<>();
        Set<String> seasons = new TreeSet<>();
        boolean requires = false;
        for (Map.Entry<String, BoostAsset> entry : all().entrySet()) {
            BoostAsset boost = entry.getValue();
            if (boost == null) {
                continue;
            }
            if (boost.isEnabled() && boost.isStanding()) {
                kept.add(boost);
            }
            if (boost.liveRequires() != null) {
                widened.add(entry.getKey());
            }
            if (boost.isEnabled() && !boost.isStanding()) {
                if (boost.season() != null) {
                    seasons.add(boost.season().toLowerCase(Locale.ROOT));
                }
                requires |= boost.liveRequires() != null;
            }
        }
        kept.sort(Comparator.comparing(BoostAsset::getId, String.CASE_INSENSITIVE_ORDER));
        standing = List.copyOf(kept);
        calendarTiles = new CalendarTiles(Set.copyOf(seasons), requires);
        StoreGates.reportWidened(MOD_GATE_STORE, widened);
        GainPlans.invalidateAll();
        TimedBuffs.refreshAllOnline();
    }
}
