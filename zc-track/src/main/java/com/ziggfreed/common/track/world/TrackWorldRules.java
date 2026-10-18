package com.ziggfreed.common.track.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.track.gain.GainPlans;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.MatchRank;

/**
 * The track rules a world runs under ({@link TrackRules}), resolved once per world and kept until the rules or the
 * gameplay configs load again.
 *
 * <p><b>Native wins where both apply.</b> A world whose own gameplay config writes {@code Plugin.ZigTrackRules}
 * reads that group, whole; only a world whose config writes none reads the {@link TrackWorldRuleAsset} files,
 * whose most specific {@code Where} wins (the {@link MatchRank} ladder; a tie keeps the first by id). Whole, never
 * leaf by leaf: a world's rules are read in one place, so what a world does is what one file says. A world neither
 * names runs under no rules: every gain lands as anywhere else.
 *
 * <p>The gain pipeline's {@code World} factor reads it when a plan is built, which happens again after every world
 * change; the grant pass (SK2d) asks {@link #grantsOn}.
 */
public final class TrackWorldRules {

    private static final AtomicLong GENERATION = new AtomicLong();

    /** What a world resolved to, at the generation it was resolved under (null rules: none apply). */
    private record Held(long generation, @Nullable TrackRules rules) {
    }

    private static final Map<World, Held> BY_WORLD = new ConcurrentHashMap<>();

    static {
        WorldEvictors.registerEvictor(BY_WORLD::remove);
    }

    private TrackWorldRules() {
    }

    /**
     * The rules {@code world} runs under, or null when none apply (or there is no world). World thread: a pattern
     * reads the world's identity.
     */
    @Nullable
    public static TrackRules forWorld(@Nullable World world) {
        if (world == null) {
            return null;
        }
        long generation = GENERATION.get();
        Held held = BY_WORLD.get(world);
        if (held != null && held.generation() == generation) {
            return held.rules();
        }
        TrackRules rules = resolve(world);
        BY_WORLD.put(world, new Held(generation, rules));
        return rules;
    }

    /** Does a track's grant of {@code kind} stay on in {@code world}? Yes wherever no rules say otherwise. */
    public static boolean grantsOn(@Nullable World world, @Nullable String kind) {
        TrackRules rules = forWorld(world);
        return rules == null || rules.grantsOn(kind);
    }

    /**
     * The rules or the gameplay configs loaded again: every world reads its rules again on its next ask, and every
     * compiled gain plan is built again.
     */
    public static void invalidate() {
        GENERATION.incrementAndGet();
        GainPlans.invalidateAll();
    }

    /** The world's own gameplay config's group, else the most specific rule file naming it, else null. */
    @Nullable
    private static TrackRules resolve(@Nonnull World world) {
        try {
            TrackRules own = TrackRules.of(world.getGameplayConfig());
            if (own != null) {
                return own;
            }
        } catch (Throwable t) {
            SafeLog.fine("[track] a world's gameplay config could not be read for its track rules: "
                    + t.getMessage());
        }
        Map<String, TrackWorldRuleAsset> all = TrackWorldRuleConfig.getInstance().all();
        List<String> ids = new ArrayList<>(all.keySet());
        Collections.sort(ids);
        TrackWorldRuleAsset best = null;
        MatchRank bestRank = null;
        for (String id : ids) {
            TrackWorldRuleAsset rule = all.get(id);
            if (rule == null || !rule.isEnabled() || rule.where() == null || rule.rules() == null) {
                continue;
            }
            MatchRank rank;
            try {
                rank = rule.where().match(world);
            } catch (Throwable t) {
                SafeLog.fine("[track] the track world rule '" + id + "' could not be matched: " + t.getMessage());
                continue;
            }
            if (rank != null && rank.isMoreSpecificThan(bestRank)) {
                best = rule;
                bestRank = rank;
            }
        }
        return best == null ? null : best.rules();
    }
}
