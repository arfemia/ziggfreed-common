package com.ziggfreed.common.objectives.title;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of {@link TitleAsset}, keyed by title id. The one place
 * a reader asks whether a title is on offer ({@link #shown}), in what order a player's titles
 * list ({@link #listing}), and how a player stands against what is on offer ({@link #tally}), so
 * the picker, the menus, the Settings tile and a command agree.
 */
public final class TitleConfig extends AbstractKeyedAssetConfig<TitleAsset> {

    /**
     * A player's titles against what is on offer, each list lower-cased in picker order: the earned
     * titles on offer, the titles on offer still to earn that they may see (every one not
     * {@code Hidden}), and how many {@code Hidden} ones they have still to earn. Its {@link #earnedCount}
     * of {@link #total} is every "n of N" a title surface shows.
     */
    public record Tally(@Nonnull List<String> earned, @Nonnull List<String> toEarn, int secret) {

        /** Nothing on offer and nothing earned. */
        public static final Tally NONE = new Tally(List.of(), List.of(), 0);

        public Tally {
            earned = List.copyOf(earned);
            toEarn = List.copyOf(toEarn);
        }

        /** The n of "n of N": the titles on offer they have earned. */
        public int earnedCount() {
            return earned.size();
        }

        /** The N of "n of N": those plus the ones still to earn they may see; a Hidden title counts once earned. */
        public int total() {
            return earned.size() + toEarn.size();
        }
    }

    private static final TitleConfig INSTANCE = new TitleConfig();

    private TitleConfig() {
    }

    @Nonnull
    public static TitleConfig getInstance() {
        return INSTANCE;
    }

    /** The folded title under {@code titleId} when one exists and is on offer, else null. */
    @Nullable
    public TitleAsset shown(@Nullable String titleId) {
        if (titleId == null || titleId.isBlank()) {
            return null;
        }
        TitleAsset title = resolve(titleId.trim());
        return title != null && title.enabled() ? title : null;
    }

    /**
     * Of {@code titleIds}, the ids on offer, lower-cased, in picker order: {@code Order}, then id.
     * Unknown, switched-off and repeated ids are left out.
     */
    @Nonnull
    public List<String> listing(@Nonnull Collection<String> titleIds) {
        Set<String> seen = new LinkedHashSet<>();
        List<TitleAsset> offered = new ArrayList<>();
        for (String id : titleIds) {
            TitleAsset title = id == null ? null : shown(id);
            if (title != null && seen.add(title.getId().toLowerCase(Locale.ROOT))) {
                offered.add(title);
            }
        }
        offered.sort(Comparator.comparingInt(TitleAsset::order).thenComparing(TitleAsset::getId));
        List<String> ids = new ArrayList<>(offered.size());
        for (TitleAsset title : offered) {
            ids.add(title.getId());
        }
        return ids;
    }

    /**
     * How a player holding {@code unlocked} stands against every title on offer: what they have earned, what they
     * may see still to earn, and how many Hidden titles stay a secret from them (R166).
     */
    @Nonnull
    public Tally tally(@Nonnull Collection<String> unlocked) {
        List<String> earned = listing(unlocked);
        List<String> toEarn = new ArrayList<>();
        int secret = 0;
        for (String id : listing(ids())) {
            if (earned.contains(id)) {
                continue;
            }
            TitleAsset title = shown(id);
            if (title != null && title.hidden()) {
                secret++;
            } else {
                toEarn.add(id);
            }
        }
        return new Tally(earned, toEarn, secret);
    }

    /** Whether any title is on offer at all, a Hidden one included. */
    public boolean anyOnOffer() {
        for (String id : ids()) {
            if (shown(id) != null) {
                return true;
            }
        }
        return false;
    }
}
