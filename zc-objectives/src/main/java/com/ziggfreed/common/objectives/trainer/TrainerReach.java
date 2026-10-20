package com.ziggfreed.common.objectives.trainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementPositionCache;
import com.ziggfreed.common.npc.placement.runtime.PlacedNpcComponent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Whether a player stands near enough their trainer to learn or buy (its block's {@code Reach}, 8 blocks unless
 * written; 0 or less, anywhere), asked on the server at every Learn and every service press, never trusted from the
 * page. Near means within reach of the trainer's NPC the page was opened at (its live position), or of any spot the
 * placement stood one in the player's own world (the placement's position cache, which every sweep refills for a
 * standing NPC). World thread.
 */
final class TrainerReach {

    private TrainerReach() {
    }

    /** Whether the player at {@code at} may act on the trainer from where they stand. */
    static boolean near(@Nonnull TrainerAt at) {
        double reach = at.trainer().reach();
        if (reach <= 0) {
            return true;
        }
        try {
            Vector3d me = position(at.store(), at.ref());
            if (me == null) {
                return false;
            }
            double limit = reach * reach;
            Ref<EntityStore> npc = at.npc();
            if (npc != null && npc.isValid() && npc.getStore() == at.store() && standsFor(at, npc)) {
                Vector3d them = position(at.store(), npc);
                if (them != null && me.distanceSquared(them) <= limit) {
                    return true;
                }
            }
            World world = at.store().getExternalData().getWorld();
            if (world == null) {
                return false;
            }
            for (NpcPlacementPositionCache.Entry spot
                    : NpcPlacementPositionCache.forPlacement(world.getName(), at.placementId())) {
                if (me.distanceSquared(spot.x(), spot.y(), spot.z()) <= limit) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            SafeLog.fine("[trainer] could not read how near a player stands to a trainer: " + t.getMessage());
            return false;
        }
    }

    /** Whether {@code npc} was stood up by the trainer's own placement (its {@code PlacedNpc} stamp). */
    private static boolean standsFor(@Nonnull TrainerAt at, @Nonnull Ref<EntityStore> npc) {
        var type = PlacedNpcComponent.getComponentType();
        PlacedNpcComponent stamp = type == null ? null : at.store().getComponent(npc, type);
        return stamp != null && stamp.placementId != null && stamp.placementId.equalsIgnoreCase(at.placementId());
    }

    @Nullable
    private static Vector3d position(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        Vector3d live = transform == null ? null : transform.getPosition();
        return live == null ? null : new Vector3d(live);
    }
}
