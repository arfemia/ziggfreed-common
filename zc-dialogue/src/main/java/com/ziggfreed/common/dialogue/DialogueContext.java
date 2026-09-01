package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.state.DialogueFlagStore;
import com.ziggfreed.common.dialogue.state.DialogueMemory;
import com.ziggfreed.common.dialogue.type.DialogueConditionEvaluator;

/**
 * The opaque per-player evaluation handle the engine threads through condition
 * checks. The engine never inspects the consumer-specific parts: it only forwards
 * the context to the registered {@link DialogueConditionEvaluator}s and reads the
 * generic flag store. A consumer's evaluators reach their own domain state
 * (quest/skill components, ...) via {@link #payload(Class)}.
 *
 * <p>Built per render by the page itself; the richer {@link DialogueExecContext} (adds
 * dialogue/node/option) is built per click for action execution.
 */
public interface DialogueContext {

    @Nonnull Store<EntityStore> store();

    @Nonnull Ref<EntityStore> ref();

    /**
     * The talking player, DERIVED from the store rather than carried beside it, or null when the
     * ref no longer resolves one (or resolves something that is not a player). Read it once and
     * guard it: a context is built per render and the entity behind it can go away between two.
     */
    @Nullable PlayerRef playerRef();

    @Nonnull Player player();

    /** The id the dialogue is being talked through (an NPC id), or null; resolves {@code @self}. */
    @Nullable String contextId();

    /**
     * The dialogue this context was built for, or null when it was built without one. Needed by
     * anything that resolves a per-dialogue name (a declared {@link DialogueMemory}, a
     * {@code Once} key); {@link DialogueExecContext} always has it.
     */
    @Nullable default NpcDialogue dialogue() { return null; }

    /** The per-player dialogue state store behind {@code Once} and the declared {@code Memories}. */
    @Nonnull DialogueFlagStore flags();

    /**
     * Retrieve the consumer-specific payload a registered evaluator/handler needs
     * (e.g. a holder of the player's quest + skill components). Returns null when
     * no payload of that type was supplied. The engine itself never calls this.
     */
    @Nullable <T> T payload(@Nonnull Class<T> type);
}
