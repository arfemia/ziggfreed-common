package com.ziggfreed.common.npc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A surface that can show a player what a character has to offer.
 *
 * <p>The sibling of {@link com.ziggfreed.common.dialogue.quest.QuestDialogueHost}, one step earlier
 * in a quest's life: that one puts the conversation a settled quest leads to on the screen, this one
 * puts the list a player opens at a character. A consumer registers its own quest panel once at
 * setup with {@link NpcQuestListHosts} and the shared {@code Quests} destination then drives it, so
 * an author writes {@code "Open": "Quests"} and never names a page.
 *
 * <p>{@code npcId} is the character the list is about, already resolved by whoever knew (a press-F at
 * a placement knows exactly who stands there). It is null only where the moment genuinely had nobody
 * in front of the player, which a host may serve as the player's own list or decline.
 *
 * <p>{@code ref} is what the page should be opened ON - the character's own entity where there is
 * one, the player otherwise.
 *
 * <p>World thread.
 */
public interface NpcQuestListHost {

    /**
     * Open it, with ONE quest called out when the moment was about a particular quest.
     *
     * <p>{@code highlightQuestId} is what a conversation's {@code Start} quest row sends the player
     * here about: a ready quest never takes a conversation over by itself, so the beat that surfaces
     * one routes HERE naming it, and the player lands looking at the quest they pressed rather than
     * at whichever row happened to sort first. It is null everywhere else, which is the ordinary
     * "just show me the list" open.
     *
     * <p>Singling a row out is a COURTESY, never a condition: a host that cannot do it still opens
     * the plain list, because opening the list is the part the player asked for.
     *
     * <p>Return true ONLY when the host actually took over the screen. A host that declines does not
     * stop the walk: another mod may be able to serve the same list.
     */
    boolean open(@Nullable String npcId, @Nullable String highlightQuestId,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef, @Nonnull Player player);
}
