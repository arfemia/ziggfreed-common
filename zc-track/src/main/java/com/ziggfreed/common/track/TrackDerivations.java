package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.track.curve.TrackCurves;
import com.ziggfreed.common.track.source.TrackSources;

/**
 * Every derived track's sources, resolved once per fold: kept until a track fold ({@link TrackSources#generation},
 * which every track fold moves) or a curve fold ({@link TrackCurves#generation}) moves, so the hot path (a level read
 * per skill per gain) looks nothing up twice. A resolution that spans a fold lands in the folded-away memo and is
 * dropped with it.
 */
final class TrackDerivations {

    /** One fold's memo: each track's derivation (empty for an ordinary or unusable one), and the derived tracks over each. */
    private record Held(long epoch, @Nonnull Map<String, Optional<TrackDerivation>> byId,
                        @Nonnull Map<String, List<TrackDef>> over) {
    }

    @Nullable private static volatile Held held;

    private TrackDerivations() {
    }

    /**
     * {@code track}'s derivation, or null when it is an ordinary track or has no usable source. {@code track} is a
     * switched-on track with a usable id.
     */
    @Nullable
    static TrackDerivation of(@Nonnull TrackAsset track, @Nonnull CurveTable table) {
        if (!track.isDerived()) {
            return null;
        }
        Held memo = held();
        String key = TrackKey.normalize(track.getId());
        Optional<TrackDerivation> known = memo.byId().get(key);
        if (known == null) {
            known = Optional.ofNullable(resolve(track, table));
            memo.byId().put(key, known);
        }
        return known.orElse(null);
    }

    /** The usable derived tracks reading {@code trackId} (any case), in track order; empty for none. */
    @Nonnull
    static List<TrackDef> over(@Nullable String trackId) {
        String wanted = TrackKey.normalize(trackId);
        if (wanted.isEmpty()) {
            return List.of();
        }
        Held memo = held();
        List<TrackDef> known = memo.over().get(wanted);
        if (known != null) {
            return known;
        }
        List<TrackDef> out = new ArrayList<>();
        for (TrackAsset track : TrackConfig.getInstance().liveTracks()) {
            if (!track.isDerived()) {
                continue;
            }
            CurveTable table = TrackCurves.table(track.curveId());
            TrackDerivation derivation = table == null ? null : of(track, table);
            if (derivation != null && derivation.readsFrom(wanted)) {
                out.add(derivation.def());
            }
        }
        List<TrackDef> kept = List.copyOf(out);
        memo.over().put(wanted, kept);
        return kept;
    }

    /** {@code track}'s sources as loaded now, or null when none is usable. */
    @Nullable
    private static TrackDerivation resolve(@Nonnull TrackAsset track, @Nonnull CurveTable table) {
        TrackAsset.Derived derived = track.derived();
        if (derived == null) {
            return null;
        }
        String self = TrackKey.normalize(track.getId());
        Set<String> seen = new LinkedHashSet<>();
        seen.add(self);
        List<TrackDef> sources = new ArrayList<>();
        TrackConfig config = TrackConfig.getInstance();
        for (String id : derived.tracks()) {
            add(config.live(id), seen, sources);
        }
        for (String category : derived.categories()) {
            for (TrackAsset candidate : config.liveTracks()) {
                if (category.equalsIgnoreCase(candidate.category() == null ? "" : candidate.category())) {
                    add(candidate, seen, sources);
                }
            }
        }
        return sources.isEmpty() ? null
                : new TrackDerivation(new TrackDef(track, table), derived.reading(), sources);
    }

    /** Add {@code candidate} as a source when it is usable, not derived and not seen yet. */
    private static void add(@Nullable TrackAsset candidate, @Nonnull Set<String> seen,
            @Nonnull List<TrackDef> sources) {
        if (candidate == null || candidate.isDerived()) {
            return;
        }
        CurveTable table = TrackCurves.table(candidate.curveId());
        if (table != null && seen.add(TrackKey.normalize(candidate.getId()))) {
            sources.add(new TrackDef(candidate, table));
        }
    }

    @Nonnull
    private static Held held() {
        long epoch = ((long) TrackSources.generation() << 32) | (TrackCurves.generation() & 0xFFFFFFFFL);
        Held memo = held;
        if (memo == null || memo.epoch() != epoch) {
            memo = new Held(epoch, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
            held = memo;
        }
        return memo;
    }
}
