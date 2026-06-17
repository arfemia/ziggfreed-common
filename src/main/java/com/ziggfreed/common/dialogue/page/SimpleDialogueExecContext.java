package com.ziggfreed.common.dialogue.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.DialogueFlagStore;
import com.ziggfreed.common.dialogue.NpcDialogue;

/**
 * A ready-made {@link DialogueExecContext} so a consumer's
 * {@link DialogueContextFactory} is a one-liner: hand it the engine handles, the
 * per-player {@link DialogueFlagStore}, and an optional domain payload (retrieved
 * by registered evaluators/handlers via {@link #payload(Class)}). Flag access
 * delegates to the supplied store; {@link #payload(Class)} returns the payload when
 * it is an instance of the requested type, else null.
 */
public final class SimpleDialogueExecContext implements DialogueExecContext {

    private final Store<EntityStore> store;
    private final Ref<EntityStore> ref;
    private final PlayerRef playerRef;
    private final Player player;
    @Nullable private final String contextId;
    private final DialogueFlagStore flags;
    @Nullable private final Object payload;
    private final NpcDialogue dialogue;
    private final String nodeId;
    private final int optionIndex;

    public SimpleDialogueExecContext(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                     @Nonnull PlayerRef playerRef, @Nonnull Player player,
                                     @Nullable String contextId, @Nonnull DialogueFlagStore flags,
                                     @Nullable Object payload, @Nonnull NpcDialogue dialogue,
                                     @Nonnull String nodeId, int optionIndex) {
        this.store = store;
        this.ref = ref;
        this.playerRef = playerRef;
        this.player = player;
        this.contextId = contextId;
        this.flags = flags;
        this.payload = payload;
        this.dialogue = dialogue;
        this.nodeId = nodeId;
        this.optionIndex = optionIndex;
    }

    @Override @Nonnull public Store<EntityStore> store() { return store; }

    @Override @Nonnull public Ref<EntityStore> ref() { return ref; }

    @Override @Nonnull public PlayerRef playerRef() { return playerRef; }

    @Override @Nonnull public Player player() { return player; }

    @Override @Nullable public String contextId() { return contextId; }

    @Override @Nonnull public DialogueFlagStore flags() { return flags; }

    @Override @Nonnull public NpcDialogue dialogue() { return dialogue; }

    @Override @Nonnull public String nodeId() { return nodeId; }

    @Override public int optionIndex() { return optionIndex; }

    @Override
    @Nullable
    public <T> T payload(@Nonnull Class<T> type) {
        return type.isInstance(payload) ? type.cast(payload) : null;
    }
}
