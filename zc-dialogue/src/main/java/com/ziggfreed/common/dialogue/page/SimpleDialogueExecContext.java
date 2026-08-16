package com.ziggfreed.common.dialogue.page;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.DialogueFlagStore;
import com.ziggfreed.common.dialogue.DialogueMemories;
import com.ziggfreed.common.dialogue.DialoguePayloads;
import com.ziggfreed.common.dialogue.NpcDialogue;

/**
 * A ready-made {@link DialogueExecContext} so a consumer's
 * {@link DialogueContextFactory} is a one-liner: hand it the engine handles and an optional domain
 * payload (retrieved by registered evaluators/handlers via {@link #payload(Class)}).
 * {@link #payload(Class)} returns that payload when it is an instance of the requested type; on a
 * miss it falls through to whatever the requested type's owner registered with
 * {@link DialoguePayloads}, so a mod's own actions and conditions still answer correctly in a
 * conversation another mod opened.
 *
 * <p><b>Built, read and discarded on the world thread.</b> One context serves a whole render and
 * every condition evaluated during it, so the two answers it can only get by asking somebody else -
 * the memory store behind {@link #flags()} and a fallback payload - are worked out on first use and
 * kept for the rest of its life. Those two fields are the only mutable state here and they carry no
 * synchronization, because a context never leaves the thread that built it.
 *
 * <p><b>State storage is not a parameter.</b> Where a memory is kept is decided by what its author
 * declared and resolved by {@link DialogueMemories}, so this context asks that surface rather than
 * taking a store from the consumer. There is deliberately no constructor that accepts one: a mod
 * supplying its own would be answering a question its authors already answered, per memory, in
 * their own files.
 */
public final class SimpleDialogueExecContext implements DialogueExecContext {

    /** Stands in for "asked, and the answer was nothing", so a miss is not re-asked. */
    private static final Object NO_PAYLOAD = new Object();

    private final Store<EntityStore> store;
    private final Ref<EntityStore> ref;
    private final PlayerRef playerRef;
    private final Player player;
    @Nullable private final String contextId;
    @Nullable private DialogueFlagStore flags;
    @Nullable private final Object payload;
    /** Fallback payloads already asked for, {@link #NO_PAYLOAD} where nobody had one to give. */
    @Nullable private Map<Class<?>, Object> fallbacks;
    private final NpcDialogue dialogue;
    private final String nodeId;
    private final int optionIndex;

    public SimpleDialogueExecContext(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                     @Nonnull PlayerRef playerRef, @Nonnull Player player,
                                     @Nullable String contextId,
                                     @Nullable Object payload, @Nonnull NpcDialogue dialogue,
                                     @Nonnull String nodeId, int optionIndex) {
        this.store = store;
        this.ref = ref;
        this.playerRef = playerRef;
        this.player = player;
        this.contextId = contextId;
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

    /**
     * The state store behind {@code Once} and the declared {@code Memories}, resolved on first use.
     * A context is built on every render AND every click, and most conversations never read or
     * write a memory at all, so the lookup happens when something actually asks for it.
     */
    @Override
    @Nonnull
    public DialogueFlagStore flags() {
        DialogueFlagStore resolved = flags;
        if (resolved == null) {
            resolved = DialogueMemories.storeFor(store, ref, playerRef);
            flags = resolved;
        }
        return resolved;
    }

    @Override @Nonnull public NpcDialogue dialogue() { return dialogue; }

    @Override @Nonnull public String nodeId() { return nodeId; }

    @Override public int optionIndex() { return optionIndex; }

    @Override
    @Nullable
    public <T> T payload(@Nonnull Class<T> type) {
        // The payload the opening mod packed in wins, always and exactly: a conversation this mod
        // opened behaves the same as it did before anything was registered process-wide. Only a
        // genuine miss - somebody else's conversation, carrying nothing of this shape - falls
        // through to whichever mod said how to build one for any player.
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        // Asked at most once per context per class. Building somebody else's payload is not a cheap
        // read - a mod may reconcile the player's saved state while it is at it - and every gated
        // option in a render asks again through this same context, so the answer is kept.
        Map<Class<?>, Object> cache = fallbacks;
        if (cache == null) {
            cache = new HashMap<>(2);
            fallbacks = cache;
        }
        Object known = cache.get(type);
        if (known == null) {
            Object built = DialoguePayloads.resolve(type, store, ref, playerRef, player);
            known = built == null ? NO_PAYLOAD : built;
            cache.put(type, known);
        }
        return known == NO_PAYLOAD ? null : type.cast(known);
    }
}
