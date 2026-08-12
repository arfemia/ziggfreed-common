package com.ziggfreed.common.npc;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.CommonLog;

/**
 * Registers the generic {@code ziggfreed-common} custom NPC actions with the engine's
 * {@link NPCPlugin}. Registration must happen BEFORE any NPC-role asset that references
 * a {@code Type} below is loaded, or the role silently fails to parse (same contract as
 * the engine's own {@code OpenBarterShop} registration).
 *
 * <p>The two entry points differ in WHO calls them, and it is not arbitrary.
 * {@link #register()} is CONSUMER-called from its own plugin {@code setup()}, because
 * opening a dialogue needs that consumer's {@link NpcDialogueDepsRegistry} wiring to mean
 * anything. {@link #registerTalkCredit()} is COMMON-called from this library's own plugin,
 * because crediting a conversation needs nothing from anybody.
 *
 * <p>Both are idempotent and guarded: a second call (a second consumer mod that also
 * depends on this lib) is a no-op, and a failure degrades to a logged warning rather than
 * a throw into the caller's {@code setup()}.
 */
public final class NpcActions {

    /** The {@code Type} id a role authors to open a dialogue on press-F ({@link ActionOpenDialogue}). */
    public static final String OPEN_DIALOGUE_TYPE = "ZigOpenDialogue";

    /** The {@code Type} id a role authors to credit a conversation on press-F ({@link ActionTalkCredit}). */
    public static final String TALK_CREDIT_TYPE = "ZigTalkCredit";

    private static volatile boolean registered = false;

    private static volatile boolean talkCreditRegistered = false;

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

    /** Registers {@link ActionTalkCredit} as {@code "ZigTalkCredit"} (idempotent, guarded). */
    public static synchronized void registerTalkCredit() {
        if (talkCreditRegistered) {
            return;
        }
        try {
            NPCPlugin npc = NPCPlugin.get();
            if (npc == null) {
                warn("[NpcActions] NPCPlugin not available; " + TALK_CREDIT_TYPE + " not registered");
                return; // not yet available - allow a later retry (talkCreditRegistered stays false)
            }
            npc.registerCoreComponentType(TALK_CREDIT_TYPE, BuilderActionTalkCredit::new);
            talkCreditRegistered = true;
            info("[NpcActions] registered NPC action: " + TALK_CREDIT_TYPE);
        } catch (Throwable t) {
            warn("[NpcActions] failed to register " + TALK_CREDIT_TYPE + ": " + t.getMessage());
        }
    }

    private static void info(@Nonnull String msg) {
        try {
            CommonLog.LOGGER.atInfo().log("%s", msg);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM; swallow.
        }
    }

    private static void warn(@Nonnull String msg) {
        try {
            CommonLog.LOGGER.atWarning().log("%s", msg);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM; swallow.
        }
    }
}
