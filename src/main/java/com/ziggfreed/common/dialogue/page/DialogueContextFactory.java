package com.ziggfreed.common.dialogue.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.NpcDialogue;

/**
 * Builds the per-player {@link DialogueExecContext} the engine threads through
 * condition checks and action execution. This is THE per-player-state seam: the
 * consumer fetches its own flag store + domain payload (quest/skill components,
 * ...) here and packs them into the context (e.g. via
 * {@link SimpleDialogueExecContext}). Called once per render (with the current node
 * and option index {@code -1}) and once per click (with the chosen node + index).
 */
@FunctionalInterface
public interface DialogueContextFactory {

    @Nonnull
    DialogueExecContext create(@Nonnull NpcDialogue dialogue, @Nonnull String nodeId, int optionIndex,
                               @Nullable String contextId, @Nonnull Ref<EntityStore> ref,
                               @Nonnull Store<EntityStore> store, @Nonnull PlayerRef playerRef,
                               @Nonnull Player player);
}
