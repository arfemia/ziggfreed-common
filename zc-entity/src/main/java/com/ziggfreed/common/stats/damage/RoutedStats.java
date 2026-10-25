package com.ziggfreed.common.stats.damage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * One family's consumer stats, each counted on the hits its {@link TypedDamageBonuses.Route} names (every hit when it
 * names none): what a consumer adds to a per-hit step of the library's (a weak spot, a crit, a lifesteal) over the
 * library's own stat, never a second copy of it (a weapon skill's own share, counted where its damage line counts).
 * One instance per family; registration at setup, reads on the world thread.
 */
public final class RoutedStats {

    /** One registered stat and the hits it counts on. */
    public record Source(@Nonnull String statId, @Nullable TypedDamageBonuses.Route route) {
    }

    @Nonnull
    private final List<Source> sources = new CopyOnWriteArrayList<>();

    /** Count {@code statId} on the hits {@code route} names (every hit when null); the same stat again replaces it. */
    public void register(@Nonnull String statId, @Nullable TypedDamageBonuses.Route route) {
        if (statId.isBlank()) {
            return;
        }
        sources.removeIf(s -> s.statId().equalsIgnoreCase(statId));
        sources.add(new Source(statId, route));
    }

    /** Does the holder of {@code stats} carry any registered stat off 0 (before any route is asked)? */
    public boolean anyOn(@Nonnull EntityStatMap stats) {
        for (Source source : sources) {
            if (TypedDamageBonuses.readMax(stats, source.statId()) != 0.0) {
                return true;
            }
        }
        return false;
    }

    /** The registered stats' points on this hit: each one off 0 whose route names it. */
    public double points(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Damage damage, @Nonnull EntityStatMap stats) {
        double sum = 0.0;
        for (Source source : sources) {
            double points = TypedDamageBonuses.readMax(stats, source.statId());
            if (points == 0.0 || (source.route() != null && !source.route().matches(accessor, attacker, damage))) {
                continue;
            }
            sum += points;
        }
        return sum;
    }
}
