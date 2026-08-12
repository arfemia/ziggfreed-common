package com.ziggfreed.common.npc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.DialogueTalk;

/**
 * Joins a conversation's {@code MarkTalked} beat to the talk-credit engine.
 *
 * <p>Two small halves that deliberately do not know about each other: the dialogue engine resolves
 * WHO a beat is about and stops there, and NPC identity decides what crediting that character means -
 * which ids it spreads across, whether the re-trigger window has already taken this one, and who is
 * told. This class is the one line that puts them together, installed once at plugin setup.
 */
public final class NpcTalkDialogue {

    private NpcTalkDialogue() {
    }

    /** Route every {@code MarkTalked} beat into {@link TalkCredits}. Call once from {@code setup()}. */
    public static void install() {
        DialogueTalk.install(NpcTalkDialogue::credit);
    }

    private static boolean credit(@Nonnull DialogueExecContext ctx, @Nonnull String npcId,
            @Nullable String qualifier) {
        Store<EntityStore> store;
        Ref<EntityStore> playerRef;
        PlayerRef player;
        try {
            store = ctx.store();
            playerRef = ctx.ref();
            player = ctx.playerRef();
        } catch (Throwable t) {
            // A context built without engine handles (a preview render, a test double) can read a
            // conversation but cannot credit one.
            return false;
        }
        return TalkCredits.credit(store, playerRef, player, null, npcId, qualifier);
    }
}
