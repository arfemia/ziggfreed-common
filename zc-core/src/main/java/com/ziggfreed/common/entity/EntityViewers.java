package com.ziggfreed.common.entity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;

/**
 * Delivers a packet to the players who can currently SEE an entity: the entity's own tracker record
 * ({@code EntityTrackerSystems.Visible.visibleTo}, the map the engine's beam and mount systems
 * iterate for the same purpose), so a packet that names the entity by network id (an entity-attached
 * particle system, an entity-following sound) reaches exactly the clients that already know the
 * entity and nobody else.
 *
 * <p><b>A freshly spawned entity has no viewers yet.</b> The tracker fills the record on its own
 * tick, so an entity added this tick answers zero, and so does one nobody is near. The count is the
 * caller's signal to fall back (play the cue at the entity's position instead, say), which is why
 * this answers a number rather than a boolean. Never a world-wide broadcast: a packet keyed on an
 * entity id means nothing to a client that has not been sent the entity.
 *
 * <p><b>World-thread only</b> (reads the tracker component); every engine touch is try-guarded, so a
 * bad ref or a missing component answers zero rather than throwing into the caller.
 */
public final class EntityViewers {

    private EntityViewers() {
    }

    /**
     * Writes {@code packet} to every player whose tracker currently shows {@code entity}, answering
     * how many received it: zero for a null or invalid ref, an entity with no tracker record, a
     * fresh entity the tracker has not yet shown to anyone, or one nobody is near.
     */
    public static int deliver(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> entity,
                              @Nonnull ToClientPacket packet) {
        if (entity == null || !entity.isValid()) {
            return 0;
        }
        try {
            EntityTrackerSystems.Visible visible =
                    accessor.getComponent(entity, EntityTrackerSystems.Visible.getComponentType());
            if (visible == null || visible.visibleTo == null || visible.visibleTo.isEmpty()) {
                return 0;
            }
            int delivered = 0;
            for (EntityTrackerSystems.EntityViewer viewer : visible.visibleTo.values()) {
                if (viewer == null || viewer.packetReceiver == null) {
                    continue;
                }
                viewer.packetReceiver.writeNoCache(packet);
                delivered++;
            }
            return delivered;
        } catch (Throwable t) {
            fine("deliver failed: " + t.getMessage());
            return 0;
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atFine().log("[ziggfreed-common][viewers] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
