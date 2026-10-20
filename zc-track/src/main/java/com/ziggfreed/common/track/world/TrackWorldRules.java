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
import com.ziggfreed.common.track.TrackDerived;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;
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
 * names runs under no rules: every gain lands as anywhere else. While the owner file switches the rules off
 * ({@link TrackWorldRuleConfig#ENABLED_KEY}), every world runs under none.
 *
 * <p>The gain pipeline's {@code World} factor reads it when a plan is built, which happens again after every world
 * change; the grant rebuild ({@code TrackDerived}) asks {@link #grantsOn} of every grant at every world entry.
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

    /**
     * Does a grant of {@code kind} stay on in {@code world}, for every track alike? Yes wherever no rules say
     * otherwise. A track or category entry may still take it away for one track
     * ({@link #grantsOn(World, String, String, String, String)}).
     */
    public static boolean grantsOn(@Nullable World world, @Nullable String kind) {
        TrackRules rules = forWorld(world);
        return rules == null || rules.grantsOn(kind);
    }

    /**
     * Does a grant of {@code kind} (and its broader word {@code family}) from a pick on {@code trackId} (in
     * {@code categoryId}) stay on in {@code world}? What the grant rebuild asks of every grant at every world entry.
     * World thread.
     */
    public static boolean grantsOn(@Nullable World world, @Nullable String kind, @Nullable String family,
            @Nullable String trackId, @Nullable String categoryId) {
        TrackRules rules = forWorld(world);
        return rules == null || rules.grantsOn(kind, family, trackId, categoryId);
    }

    /**
     * What a gain on {@code trackId} is multiplied by in {@code world}, read against the track's own category, as
     * the gain pipeline's {@code World} factor reads it: 0 where the world turns the track off, 1 where no rules
     * apply. For a consumer paying a gain past that factor (an exact write it still holds to the world's rules).
     * World thread.
     */
    public static double gainMultiplier(@Nullable World world, @Nonnull String trackId) {
        TrackAsset track = TrackConfig.getInstance().live(trackId);
        return gainMultiplier(world, trackId, track == null ? null : track.category());
    }

    /**
     * {@link #gainMultiplier(World, String)} for a climb no track file names (a consumer's own), with the
     * {@code categoryId} the consumer files it under (null for none).
     */
    public static double gainMultiplier(@Nullable World world, @Nonnull String trackId,
            @Nullable String categoryId) {
        TrackRules rules = forWorld(world);
        return rules == null ? 1.0 : rules.gainMultiplier(trackId, categoryId);
    }

    /**
     * The rules or the gameplay configs loaded again: every world reads its rules again on its next ask, every
     * compiled gain plan is built again, and every online player's grants are rebuilt where they stand.
     */
    public static void invalidate() {
        GENERATION.incrementAndGet();
        GainPlans.invalidateAll();
        TrackDerived.rebuildAllOnline();
    }

    /**
     * The world's own gameplay config's group, else the most specific rule file naming it, else null; null too
     * while the owner file switches the rules off.
     */
    @Nullable
    private static TrackRules resolve(@Nonnull World world) {
        if (!TrackWorldRuleConfig.getInstance().isEnabled()) {
            return null;
        }
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
