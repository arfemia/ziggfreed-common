package com.ziggfreed.common.dialogue.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.NpcDialogue;
import com.ziggfreed.common.dialogue.asset.DialogueAssetStore;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ONE way a conversation is put on the screen, because opening one is not always opening it.
 *
 * <p>A {@code Start} quest row may say "show them this instead" - the character's quest list with the
 * finished quest highlighted, a board, whatever the routing vocabulary knows - and that decision is
 * made from the player's state, so it can only be taken once there is a player to read. Taking it
 * HERE, before the page exists, is what lets the other screen simply open: a page cannot hand the
 * screen to somebody else halfway through building itself.
 *
 * <p>The ladder is walked exactly once per open. The resolved beat is handed to the page rather than
 * being worked out again inside it, which also keeps a {@code Pick} beat's draw to one draw per
 * conversation instead of one per pass.
 *
 * <p>World thread; every path either opens something or answers false, and a caller that gets false
 * still owes the player its own response.
 */
public final class DialogueOpener {

    private DialogueOpener() {
    }

    /**
     * Open {@code dialogueId} for the player in {@code ctx}, or whatever its {@code Start} routes to.
     *
     * @param contextNpcId the character the conversation is WITH, which is what {@code @self}, the
     *                     header name, the talk credit and every quest-aware line read. Falls back to
     *                     whoever the context already names.
     * @return true when a screen was taken over, false when nothing could be opened at all
     */
    public static boolean open(@Nonnull DestinationContext ctx, @Nonnull String dialogueId,
            @Nullable String contextNpcId) {
        String npcId = contextNpcId == null || contextNpcId.isBlank() ? ctx.npcId() : contextNpcId;
        NpcDialogue dialogue = DialogueAssetStore.getInstance().dialogue(dialogueId);
        if (dialogue == null) {
            // The page says so on its own screen, which is a better answer than a press-F that does
            // nothing: the player sees that this character has nothing to say and can walk away.
            return openPage(ctx, dialogueId, npcId, null);
        }

        DialogueEngine.EntryResolution entry;
        try {
            DialogueExecContext exec = new SimpleDialogueExecContext(ctx.store(), ctx.playerReference(),
                    ctx.player(), npcId, null, dialogue, "", -1);
            entry = DialogueEngine.shared().resolveEntry(dialogue, exec);
        } catch (Throwable t) {
            SafeLog.warn("[dialogue] '" + dialogueId + "' could not work out where to open: "
                    + t.getMessage());
            entry = null;
        }

        if (entry != null && entry.routes()) {
            DestinationContext routed = ctx.npcId() != null ? ctx
                    : ctx.withNpc(ctx.npcRef(), npcId, ctx.placementId());
            return Destinations.open(entry.destination(), routed);
        }
        return openPage(ctx, dialogueId, npcId, entry);
    }

    private static boolean openPage(@Nonnull DestinationContext ctx, @Nonnull String dialogueId,
            @Nullable String npcId, @Nullable DialogueEngine.EntryResolution entry) {
        PlayerRef playerRef = ctx.playerRef();
        if (playerRef == null) {
            SafeLog.fine("[dialogue] a conversation was asked for on an entity that is not a player");
            return false;
        }
        ctx.player().getPageManager().openCustomPage(ctx.pageAnchor(), ctx.store(),
                new DialoguePage(playerRef, dialogueId, npcId, ctx.npcRef(), entry));
        return true;
    }
}
