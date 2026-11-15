package com.ziggfreed.common.objectives.bonus;

import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;

/**
 * The yield a player kills with (R916): {@code KillYield}, in whole percent points, added to the chance of a bonus find
 * on a kill, the combat side of zc-world's {@code GatherYields}. The library's kill rows add it to a row's own
 * {@code Chance}, so it raises a find's odds and never rolls a second time; a chance nobody wrote (a certain find)
 * takes nothing from it. A mod's own kill finds may read the stat by {@link #YIELD_STAT}. World-thread only.
 */
public final class KillYields {

    /** The stat every kill's bonus find reads. */
    public static final String YIELD_STAT = "KillYield";

    private KillYields() {
    }

    /** {@code player}'s kill yield in whole percent points: 0 for no player, no stat map or no stat loaded. */
    public static double percentFor(@Nullable ComponentAccessor<EntityStore> store, @Nullable Ref<EntityStore> player) {
        if (store == null || player == null || !player.isValid()) {
            return 0.0;
        }
        try {
            EntityStatMap stats = store.getComponent(player, EntityStatMap.getComponentType());
            return stats == null ? 0.0 : TypedDamageBonuses.readMax(stats, YIELD_STAT);
        } catch (Throwable t) {
            return 0.0;
        }
    }
}
