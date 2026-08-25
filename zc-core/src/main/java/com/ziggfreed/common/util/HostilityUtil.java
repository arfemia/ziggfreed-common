package com.ziggfreed.common.util;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.ziggfreed.common.CommonLog;

/**
 * Hostility query using the NPC's {@link WorldSupport} attitude cache.
 *
 * <p>{@code Attitude} is an enum: IGNORE, HOSTILE, NEUTRAL, FRIENDLY, REVERED.
 * World-thread only (reads the store + NPC role); fully try-guarded.
 */
public final class HostilityUtil {

    private HostilityUtil() {
    }

    /**
     * @return true if {@code npcRef} is HOSTILE toward {@code targetRef}
     */
    public static boolean isHostileTowards(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Ref<EntityStore> targetRef
    ) {
        try {
            ComponentAccessor<EntityStore> accessor = store;
            WorldSupport worldSupport = WorldSupport.get(npcRef, accessor);
            if (worldSupport == null) {
                return false; // not a role-driven NPC (WorldSupport.get is a bare getComponent)
            }
            // getAttitude dereferences the support's attitude cache, which only exists once an
            // attitude-consuming sensor primed it; prime it ourselves like every first-party
            // caller does, so a role with no such sensor answers instead of throwing.
            worldSupport.requireAttitudeCache();
            Attitude attitude = worldSupport.getAttitude(npcRef, targetRef, accessor);

            return attitude == Attitude.HOSTILE;

        } catch (Throwable t) {
            CommonLog.LOGGER.atFine().log("HostilityUtil failed: " + t);
            return false;
        }
    }
}
