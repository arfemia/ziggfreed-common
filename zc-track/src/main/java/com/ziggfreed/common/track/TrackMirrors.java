package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.stats.MirroredStats;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.curve.TrackCurves;
import com.ziggfreed.common.track.source.TrackSources;

/**
 * A track's {@code Mirror} stat is the track's record, never the stat's fold: claimed once at setup through zc-entity's
 * {@link MirroredStats}, so every reading of a mirrored stat ({@code hytale:stat}, a proc's stat, a consumer's own
 * authored read) answers the level the mirror writes ({@link TrackService#mirror}: the best level, or the current
 * climb's under {@code Mirror.Current}), and a gear, effect or grant line on it counts nowhere. Several tracks
 * mirroring one stat read their levels summed, as their keyed modifiers sum on it. A derived track's mirror is never
 * written ({@code TrackService} keeps no climb of its own for it), so a stat one names reads its derived level here
 * (the best, or the current under {@code Mirror.Current}, off its sources), where its fold would read none.
 *
 * <p>Which stat mirrors which tracks is read off the live tracks once per fold (kept until a track fold,
 * {@link TrackSources#generation}, or a curve fold, {@link TrackCurves#generation}, moves), so the per-read test
 * looks nothing up twice.
 */
final class TrackMirrors {

    /** Why a gear line on a mirrored stat does nothing, in the raw English an audit line carries. */
    static final String WHY = "a track's level comes only from its climb";

    /** One fold's index: a mirrored stat's current id, lower-cased, to the ids of the tracks mirroring it. */
    private record Held(long epoch, @Nonnull Map<String, List<String>> tracksByStat) {
    }

    @Nullable private static volatile Held held;

    private TrackMirrors() {
    }

    /** Claim every track's mirror stat. Once, at library setup. */
    static void claim() {
        MirroredStats.claim(TrackFactors.OWNER + ":track", TrackMirrors::mirrored, TrackMirrors::read, WHY);
    }

    /** Does some switched-on track mirror {@code statId} (its current id, any case)? */
    static boolean mirrored(@Nullable String statId) {
        return statId != null && held().tracksByStat().containsKey(key(statId));
    }

    /**
     * What the mirrored stat {@code ctx} names reads for its live player: each mirroring track's level as its mirror
     * writes it, summed; null with no live player or no climb read.
     */
    @Nullable
    static Double read(@Nonnull FactorContext ctx) {
        String statId = ctx.param();
        List<String> tracks = statId == null ? null : held().tracksByStat().get(key(statId));
        UUID playerId = tracks == null ? null : TrackFactors.playerOf(ctx.store(), ctx.subject());
        if (playerId == null) {
            return null;
        }
        double sum = 0.0;
        boolean read = false;
        for (String trackId : tracks) {
            TrackReading reading = TrackService.read(TrackKey.player(playerId, trackId));
            if (reading != null) {
                sum += reading.def().asset().mirrorsCurrent() ? reading.level() : reading.best();
                read = true;
            }
        }
        return read ? sum : null;
    }

    @Nonnull
    private static String key(@Nonnull String statId) {
        return StatIndexCache.currentId(statId.trim()).toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static Held held() {
        long epoch = ((long) TrackSources.generation() << 32) | (TrackCurves.generation() & 0xFFFFFFFFL);
        Held memo = held;
        if (memo == null || memo.epoch() != epoch) {
            Map<String, List<String>> index = new HashMap<>();
            for (TrackAsset track : TrackConfig.getInstance().liveTracks()) {
                String stat = track.mirrorStat();
                if (stat != null) {
                    index.computeIfAbsent(key(stat), k -> new ArrayList<>()).add(track.getId());
                }
            }
            memo = new Held(epoch, Map.copyOf(index));
            held = memo;
        }
        return memo;
    }
}
