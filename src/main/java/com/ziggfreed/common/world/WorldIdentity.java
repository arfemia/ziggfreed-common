package com.ziggfreed.common.world;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.cast.WorldEvictors;

/**
 * The one authority on "what is this world called, in selector vocabulary" - the cached resolver
 * that turns every loaded {@link WorldSelectorAsset} into a per-world {@link WorldNameIndex}.
 *
 * <p>A world's names are the UNION across every asset whose patterns match it, each name bound to
 * the most specific {@link MatchRank} any contributing asset earned. A name is a shorthand for the
 * patterns behind it, so a rule that targets a name inherits the rank of whichever pattern
 * actually matched and sorts against inline rules in one ordering.
 *
 * <p>Resolution reads two axes off the world: its {@link World#getName() name} (matched through
 * {@link WorldNameMatcher}'s grammar) and its authored
 * {@code WorldConfig.getGameplayConfig()} - the stable, uuid-free machine key that survives an
 * instance world being torn down and re-created under a fresh random name.
 *
 * <p><b>{@link #invalidateAll()} is mandatory, not an optimization.</b> The primary world is added
 * during universe boot, BEFORE the asset load event folds {@link WorldSelectorConfig}. A world
 * resolved in that window sees an empty selector pool, and because the result is cached per world
 * it would keep an EMPTY name set for the entire life of the process - so
 * {@code Names: ["primary"]} would silently never match, everywhere, with no error anywhere.
 * {@link WorldSelectorConfig}'s merge methods therefore call this on every layer write, and a
 * consumer's config-reload command should call it too.
 *
 * <p>Cached in a {@link ConcurrentHashMap} keyed by {@link World} and registered with
 * {@link WorldEvictors}, so an unloaded world (an instance torn down after its last player left)
 * drops out with every other per-world partition. Every public method is try-guarded: a failed
 * resolution degrades to an EMPTY name set and is never cached, so a transient engine failure
 * cannot latch a world into permanent namelessness.
 */
public final class WorldIdentity {

    private static final Map<World, WorldNameIndex> CACHE = new ConcurrentHashMap<>();

    static {
        WorldEvictors.registerEvictor(CACHE::remove);
    }

    private WorldIdentity() {
    }

    /**
     * The cached selector-name index for {@code world}; {@link WorldNameIndex#EMPTY} when nothing
     * matched or the world could not be read.
     */
    @Nonnull
    public static WorldNameIndex indexFor(@Nullable World world) {
        if (world == null) {
            return WorldNameIndex.EMPTY;
        }
        WorldNameIndex cached = CACHE.get(world);
        if (cached != null) {
            return cached;
        }
        WorldNameIndex resolved;
        try {
            resolved = resolve(world.getName(), world.getWorldConfig().getGameplayConfig(),
                    WorldSelectorConfig.getInstance().all().values());
        } catch (Throwable t) {
            // Never cache a failure - a transient read error must not latch this world as nameless.
            warn("WorldIdentity failed to resolve a world's selector names: " + t.getMessage());
            return WorldNameIndex.EMPTY;
        }
        CACHE.put(world, resolved);
        return resolved;
    }

    /** Every selector name {@code world} carries (lower-cased); empty when nothing matched. */
    @Nonnull
    public static Set<String> namesFor(@Nullable World world) {
        return indexFor(world).names();
    }

    /**
     * The {@link MatchRank} at which {@code world} earned {@code name}, or {@code null} when the
     * world does not carry it. This is the rank a rule referencing that name inherits.
     */
    @Nullable
    public static MatchRank rankOf(@Nullable World world, @Nullable String name) {
        return indexFor(world).rankOf(name);
    }

    /** Does {@code world} carry {@code name}? */
    public static boolean has(@Nullable World world, @Nullable String name) {
        return indexFor(world).has(name);
    }

    /**
     * Drop every cached resolution. Call whenever the selector pool changes - the asset merge
     * (which {@link WorldSelectorConfig} does for you) and a consumer's config-reload command.
     * See the class javadoc for what forgetting this costs.
     */
    public static void invalidateAll() {
        CACHE.clear();
    }

    /** Drop one world's cached resolution (the evictor path, and a targeted reload). */
    public static void invalidate(@Nullable World world) {
        if (world != null) {
            CACHE.remove(world);
        }
    }

    /** How many worlds currently hold a cached resolution (diagnostics / tests). */
    public static int cachedWorldCount() {
        return CACHE.size();
    }

    /**
     * The PURE resolver: fold a selector pool into one world's {@code name -> rank} index. No
     * engine coupling - {@link #indexFor} is this plus the cache and the world reads.
     *
     * <p>Every asset is scored independently and its names are unioned in, keeping the most
     * specific rank per name. Two assets contributing the same name therefore BOTH apply, which
     * is the whole point of the id being a pure address.
     */
    @Nonnull
    public static WorldNameIndex resolve(@Nullable String worldName, @Nullable String worldGameplayConfig,
            @Nonnull Collection<WorldSelectorDef> pool) {
        Map<String, MatchRank> ranks = new LinkedHashMap<>();
        for (WorldSelectorDef def : pool) {
            if (def == null) {
                continue;
            }
            MatchRank rank = def.rankFor(worldName, worldGameplayConfig);
            if (rank == null) {
                continue;
            }
            for (String name : def.names()) {
                ranks.merge(name, rank, (existing, candidate) ->
                        candidate.isMoreSpecificThan(existing) ? candidate : existing);
            }
        }
        return WorldNameIndex.of(ranks);
    }

    private static void warn(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
