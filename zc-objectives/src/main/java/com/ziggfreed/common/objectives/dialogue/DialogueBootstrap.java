package com.ziggfreed.common.objectives.dialogue;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.LibraryOwner;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.state.DialogueMemories;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;
import com.ziggfreed.common.objectives.store.ZigProgressDialogueStore;
import com.ziggfreed.common.quest.QuestResets;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fills the dialogue engine's declared seams at plugin {@code setup()}: the factor vocabulary its
 * {@code Factor} conditions resolve against, the persistent memory store, and the active-objective
 * header note. Three ordered phases, each called once from
 * {@code ZiggfreedCommonPlugin.setup()}, which stays the one authority on call ORDER.
 *
 * <p>The dialogue module declares each of these seams and structurally cannot fill them itself:
 * the {@code hytale:} factor standard library lives in the entity module it cannot see, the
 * persistent memory backend is the progress component kept in THIS module (the one that depends on
 * the dialogue one), and the header reads the quest engine. This module sees every one of those
 * ends, and nothing depends on it, so filling them from here closes no cycle.
 */
public final class DialogueBootstrap {

    private DialogueBootstrap() {
    }

    /**
     * The header note a conversation can show under the speaker's name. Contributed here rather
     * than by a consumer because it reads the library's own quest engine, so it answers for every
     * mod's quests and no mod has to ship one to get it.
     */
    public static void registerActiveObjectiveHeader() {
        try {
            ActiveObjectiveHeader.register(LibraryOwner.NAME);
        } catch (Throwable t) {
            SafeLog.warn("[dialogue] could not contribute the active-objective header", t);
        }
    }

    /**
     * The factor vocabulary a conversation's {@code Factor} conditions resolve against: the
     * portable {@code hytale:} standard library, installed into the dialogue engine's ONE factor
     * slot so a pack gates an option on native engine data - a stat channel, what the player is
     * holding - with no Java at all, on a server running nothing but this jar. It is wired from
     * this module because the dialogue module cannot see the entity module that owns the standard
     * library, and this module sees both.
     *
     * <p>The slot is first-install-wins and this library loads before every consumer, so a
     * consumer offering its own registry is refused and loses nothing: an id a mod's own
     * conversations gate on belongs in the process-wide {@code FactorContributions} table, which
     * every registry consults whoever holds the slot.
     */
    public static void registerDialogueVocabulary() {
        try {
            DialogueEngine.installFactors(LibraryOwner.NAME, dialogueFactorVocabulary());
        } catch (Throwable t) {
            SafeLog.warn("[dialogue] could not install the dialogue factor vocabulary", t);
        }
    }

    /** The portable engine readings about the acting player; nothing dialogue-specific in it. */
    @Nonnull
    private static FactorRegistry dialogueFactorVocabulary() {
        FactorRegistry registry = new FactorRegistry("dialogue");
        HytaleFactors.registerInto(registry, LibraryOwner.NAME);
        return registry;
    }

    /**
     * Join the dialogue engine's memory to the place a persistent one is kept, and end a session's
     * memories when the session does.
     *
     * <p>A memory whose author did not declare it {@code Session} outlives a restart, so it needs
     * the persisted progress component - which lives HERE, in the module that depends on the
     * dialogue one, so the dialogue module declares a seam it cannot fill and this module fills it
     * with its own store.
     *
     * <p>The disconnect listener is the other half of the same contract: {@code Session} means "for
     * as long as this player is connected", and something has to be the moment that ends. A consumer
     * whose own boundary is shorter - a minigame round, an instance visit - calls
     * {@code DialogueMemories.forgetSession} at that boundary too.
     *
     * <p>The quest hook is the third: a memory declared to reset with a quest has to be forgotten
     * when that quest is re-armed, and the two ends are again in modules that cannot see each other
     * - the engine reporting the re-arm sits BELOW the dialogue module that holds the memory. So
     * the progression module declares {@code QuestResets}, this module (which sees both) fills it,
     * and a declared lifetime is honoured on every server rather than by whichever consumer wrote a
     * clear of its own.
     */
    public static void registerDialogueMemories(@Nonnull PluginBase plugin) {
        try {
            DialogueMemories.install(ZigProgressDialogueStore.INSTANCE);
            QuestResets.install(DialogueMemories::forgetQuest);
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, event -> {
                PlayerRef playerRef = event.getPlayerRef();
                UUID uuid = playerRef == null ? null : playerRef.getUuid();
                if (uuid != null) {
                    DialogueMemories.forgetSession(uuid);
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[dialogue] could not wire the dialogue memory store", t);
        }
    }
}
