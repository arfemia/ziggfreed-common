package com.ziggfreed.common.dialogue.quest;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.asset.DialogueAssetStore;
import com.ziggfreed.common.dialogue.page.DialogueOpener;
import com.ziggfreed.common.ui.route.DestinationContext;

/**
 * The library's own conversation page, as the host every stored conversation can be opened through.
 *
 * <p>{@link QuestDialogueHosts} asks it LAST, after every host a consumer registered, so a quest's
 * closing conversation plays on a server running no conversation UI of its own while a consumer's own
 * screen still wins wherever it knows the conversation. It knows exactly what the shared conversation
 * store holds, and opens through the ordinary opener, told which character the beat is with, so the
 * header name, the {@code @self} targets, the talk credit and every quest-aware line behave as they
 * do when a player walks up and presses F. Through the opener rather than straight to the page,
 * because a conversation whose {@code Start} routes somewhere else hands the screen over before a page
 * is built.
 *
 * <p>No NPC entity is involved: a hand-in can be settled from a list or a button as well as in a
 * conversation, so the character is named rather than pointed at and the page anchors on the player.
 *
 * <p>World thread.
 */
final class LibraryDialogueHost implements QuestDialogueHost {

    /** The one instance; it holds nothing, and the conversation store is a singleton. */
    static final LibraryDialogueHost INSTANCE = new LibraryDialogueHost();

    private LibraryDialogueHost() {
    }

    @Override
    public boolean knows(@Nonnull String dialogueId) {
        return DialogueAssetStore.getInstance().dialogue(dialogueId) != null;
    }

    @Override
    public boolean open(@Nonnull QuestHandOff handOff, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        String dialogueId = handOff.dialogueId();
        if (dialogueId == null) {
            return false;
        }
        DestinationContext ctx = DestinationContext.of(store, ref, player).withNpc(null, handOff.npcId(), null);
        return DialogueOpener.open(ctx, dialogueId, handOff.npcId());
    }
}
