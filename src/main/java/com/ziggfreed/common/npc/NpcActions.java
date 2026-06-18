package com.ziggfreed.common.npc;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Registers the generic {@code ziggfreed-common} custom NPC actions with the engine's
 * {@link NPCPlugin}. A consumer calls {@link #register()} ONCE in its plugin
 * {@code setup()} BEFORE any NPC-role asset that references a {@code Type} below is
 * loaded, or the role silently fails to parse (same contract as the engine's own
 * {@code OpenBarterShop} registration).
 *
 * <p>{@link #register()} is idempotent and guarded: a second call (a second consumer
 * mod that also depends on this lib) is a no-op, and a failure degrades to a logged
 * warning rather than a throw into the consumer's {@code setup()}.
 */
public final class NpcActions {

    /** The {@code Type} id a role authors to open a dialogue on press-F ({@link ActionOpenDialogue}). */
    public static final String OPEN_DIALOGUE_TYPE = "ZigOpenDialogue";

    private static volatile boolean registered = false;

    private NpcActions() {
    }

    /** Registers {@link ActionOpenDialogue} as {@code "ZigOpenDialogue"} (idempotent, guarded). */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        try {
            NPCPlugin npc = NPCPlugin.get();
            if (npc == null) {
                warn("[NpcActions] NPCPlugin not available; " + OPEN_DIALOGUE_TYPE + " not registered");
                return; // not yet available - allow a later retry (registered stays false)
            }
            npc.registerCoreComponentType(OPEN_DIALOGUE_TYPE, BuilderActionOpenDialogue::new);
            registered = true;
            info("[NpcActions] registered NPC action: " + OPEN_DIALOGUE_TYPE);
        } catch (Throwable t) {
            warn("[NpcActions] failed to register " + OPEN_DIALOGUE_TYPE + ": " + t.getMessage());
        }
    }

    private static void info(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atInfo().log("%s", msg);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM; swallow.
        }
    }

    private static void warn(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("%s", msg);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM; swallow.
        }
    }
}
