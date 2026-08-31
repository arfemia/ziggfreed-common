package com.ziggfreed.common.npc.placement;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the placement engine's own NPC action with the engine.
 *
 * <p>Called once from {@code NpcBootstrap.setupPlacementEngine} at library {@code setup()}, BEFORE
 * any NPC role asset that references {@link #PLACEMENT_INTERACT_TYPE} loads - a role naming an
 * unregistered action type
 * silently fails to parse, which shows up as an NPC that ignores press-F rather than as an error.
 *
 * <p>Idempotent and guarded: a second call is a no-op, and a failure logs rather than throwing
 * into {@code setup()}. Registration is left unlatched on a failure so a later retry can succeed.
 */
public final class PlacementNpcActions {

    /**
     * The {@code Type} id a base role authors inside its {@code InteractionInstruction} to run
     * whatever the placement standing in that role says press-F should do.
     */
    public static final String PLACEMENT_INTERACT_TYPE = "ZigPlacementInteract";

    private static volatile boolean registered = false;

    private PlacementNpcActions() {
    }

    /** Register {@link ActionPlacementInteract}. Idempotent, guarded. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        try {
            NPCPlugin npc = NPCPlugin.get();
            if (npc == null) {
                SafeLog.warn("[placement] NPCPlugin not available; " + PLACEMENT_INTERACT_TYPE
                        + " not registered");
                return; // Leave unlatched so a later call can retry.
            }
            npc.registerCoreComponentType(PLACEMENT_INTERACT_TYPE, BuilderActionPlacementInteract::new);
            registered = true;
            SafeLog.info("[placement] registered NPC action: " + PLACEMENT_INTERACT_TYPE);
        } catch (Throwable t) {
            SafeLog.warn("[placement] failed to register " + PLACEMENT_INTERACT_TYPE + ": " + t.getMessage());
        }
    }

    /** Has the action been registered this process? (diagnostics) */
    public static boolean isRegistered() {
        return registered;
    }
}
