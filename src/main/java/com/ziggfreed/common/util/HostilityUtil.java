package com.ziggfreed.common.util;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.blackboard.Blackboard;
import com.hypixel.hytale.server.npc.blackboard.view.attitude.AttitudeView;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Hostility query using the NPC Blackboard {@link AttitudeView}.
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
            Blackboard blackboard = store.getResource(Blackboard.getResourceType());
            if (blackboard == null) {
                return false;
            }

            AttitudeView attitudeView = blackboard.getView(AttitudeView.class, npcRef, store);
            if (attitudeView == null) {
                return false;
            }

            ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
            if (npcType == null) {
                return false;
            }

            NPCEntity npc = store.getComponent(npcRef, npcType);
            if (npc == null) {
                return false;
            }

            Role role = npc.getRole();
            if (role == null) {
                return false;
            }

            Attitude attitude = attitudeView.getAttitude(
                    npcRef, role, targetRef, (ComponentAccessor<EntityStore>) store);

            return attitude == Attitude.HOSTILE;

        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("HostilityUtil failed: " + t);
            return false;
        }
    }
}
