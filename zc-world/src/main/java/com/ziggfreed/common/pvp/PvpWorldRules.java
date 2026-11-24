package com.ziggfreed.common.pvp;

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
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.MatchRank;

/**
 * The PvP rules a world runs under ({@link PvpRules}), resolved once per world and kept until the rule files or the
 * gameplay configs load again.
 *
 * <p><b>Native wins where both apply.</b> A world whose own gameplay config writes {@code Plugin.ZigPvp} reads that
 * group, whole; only a world whose config writes none reads the {@link PvpRuleAsset} files, whose most specific
 * {@code Where} wins (the {@link MatchRank} ladder; a tie keeps the first by id). A world neither names runs under
 * every default ({@link PvpRules#DEFAULTS}); the library's own {@code Default} file names every world, so as shipped
 * that is the file a server owner tunes. A file's {@code Where} may filter on the world's PvP switch, which the engine
 * changes without a word, so a world that resolved under such a file is read again whenever its switch moves.
 */
public final class PvpWorldRules {

    private static final AtomicLong GENERATION = new AtomicLong();

    /** What a world resolved to, at the generation and PvP switch it was resolved under. */
    private record Held(long generation, boolean pvp, @Nonnull PvpRules rules) {
    }

    private static final Map<World, Held> BY_WORLD = new ConcurrentHashMap<>();

    static {
        WorldEvictors.registerEvictor(BY_WORLD::remove);
    }

    private PvpWorldRules() {
    }

    /** The rules {@code world} runs under; every default when none apply or there is no world. World thread. */
    @Nonnull
    public static PvpRules forWorld(@Nullable World world) {
        if (world == null) {
            return PvpRules.DEFAULTS;
        }
        long generation = GENERATION.get();
        boolean pvp = pvpOf(world);
        Held held = BY_WORLD.get(world);
        if (held != null && held.generation() == generation && held.pvp() == pvp) {
            return held.rules();
        }
        PvpRules rules = resolve(world);
        BY_WORLD.put(world, new Held(generation, pvp, rules));
        return rules;
    }

    /** The rule files or the gameplay configs loaded again: every world reads its rules again on its next ask. */
    public static void invalidate() {
        GENERATION.incrementAndGet();
    }

    /** The world's own PvP switch, false when it cannot be read. */
    private static boolean pvpOf(@Nonnull World world) {
        try {
            return world.getWorldConfig().isPvpEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The world's own gameplay config's group, else the most specific enabled rule file naming it, else defaults. */
    @Nonnull
    private static PvpRules resolve(@Nonnull World world) {
        try {
            PvpRules own = PvpRules.of(world.getGameplayConfig());
            if (own != null) {
                return own;
            }
        } catch (Throwable t) {
            SafeLog.fine("[pvp] a world's gameplay config could not be read for its PvP rules: " + t.getMessage());
        }
        Map<String, PvpRuleAsset> all = PvpRuleConfig.getInstance().all();
        List<String> ids = new ArrayList<>(all.keySet());
        Collections.sort(ids);
        PvpRuleAsset best = null;
        MatchRank bestRank = null;
        for (String id : ids) {
            PvpRuleAsset rule = all.get(id);
            if (rule == null || !rule.isEnabled() || rule.where() == null || rule.rules() == null) {
                continue;
            }
            MatchRank rank;
            try {
                rank = rule.where().match(world);
            } catch (Throwable t) {
                SafeLog.fine("[pvp] the PvP rule '" + id + "' could not be matched: " + t.getMessage());
                continue;
            }
            if (rank != null && rank.isMoreSpecificThan(bestRank)) {
                best = rule;
                bestRank = rank;
            }
        }
        return best == null ? PvpRules.DEFAULTS : best.rules();
    }
}
