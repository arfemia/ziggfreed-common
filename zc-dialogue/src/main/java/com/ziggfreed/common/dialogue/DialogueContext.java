package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The opaque per-player evaluation handle the engine threads through condition
 * checks. The engine never inspects the consumer-specific parts: it only forwards
 * the context to the registered {@link DialogueConditionEvaluator}s and reads the
 * generic flag store. A consumer's evaluators reach their own domain state
 * (quest/skill components, ...) via {@link #payload(Class)}.
 *
 * <p>Built per render by a {@code DialogueContextFactory}; the richer
 * {@link DialogueExecContext} (adds dialogue/node/option) is built per click for
 * action execution.
 */
public interface DialogueContext {

    @Nonnull Store<EntityStore> store();

    @Nonnull Ref<EntityStore> ref();

    @Nonnull PlayerRef playerRef();

    @Nonnull Player player();

    /** The id the dialogue is being talked through (an NPC id), or null; resolves {@code @self}. */
    @Nullable String contextId();

    /** Dialogue-local per-player flag memory (generic {@code Flag}/{@code NotFlag} + the reward once-guard). */
    @Nonnull DialogueFlagStore flags();

    /**
     * Retrieve the consumer-specific payload a registered evaluator/handler needs
     * (e.g. a holder of the player's quest + skill components). Returns null when
     * no payload of that type was supplied. The engine itself never calls this.
     */
    @Nullable <T> T payload(@Nonnull Class<T> type);
}
