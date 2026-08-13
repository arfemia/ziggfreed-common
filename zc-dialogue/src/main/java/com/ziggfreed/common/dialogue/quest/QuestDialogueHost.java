package com.ziggfreed.common.dialogue.quest;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A surface that can put a conversation on a player's screen.
 *
 * <p>A consumer registers one with {@link QuestDialogueHosts}, wrapping whatever page routing it
 * already has. The DECISION about whether a conversation should follow a quest settling is not a
 * host's business - that lives once, in {@link QuestCompletionRouting}, so a quest log, a book and a
 * giver's own panel cannot each improvise a different answer.
 *
 * <p><b>{@code knows} and {@code open} are deliberately on ONE interface.</b> A routing decision that
 * said PLAY on a conversation nothing could actually open would leave a caller that has already
 * returned from its own refresh path staring at a dead screen. The surface that can open it is the
 * only one that can be asked whether it can.
 *
 * <p>World thread.
 */
public interface QuestDialogueHost {

    /**
     * Has this host a conversation under {@code dialogueId}? Cheap and side-effect free: it runs
     * inside the routing decision, on click and render paths.
     */
    boolean knows(@Nonnull String dialogueId);

    /**
     * Open it.
     *
     * <p>Return true ONLY when the host actually took over the screen. A caller that gets false still
     * owes the player a response and will fall back to its own refresh, so a host that declines - a
     * page manager that refused, a conversation that vanished between the decision and the click -
     * must say so rather than reporting a screen it did not paint.
     */
    boolean open(@Nonnull QuestHandOff handOff, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull Player player);
}
