package com.ziggfreed.common.objectives.store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.DialogueFlagStore;
import com.ziggfreed.common.dialogue.DialogueMemories;
import com.ziggfreed.common.util.SafeLog;

/**
 * The PERSISTENT half of dialogue state, kept on {@link ZigProgressComponent}: what a conversation
 * remembers about a player for good, saved into their world like every other progression record.
 *
 * <p>It lives in this module rather than beside the dialogue engine because of the module graph:
 * the component is here, this module depends on the dialogue one, and that edge runs one way. So
 * the dialogue module declares the {@link DialogueMemories.PersistentStore} seam and the wiring
 * root - the one place an edge between two modules is legal - fills it with this.
 *
 * <p><b>A read never creates.</b> No component means every key reads unset and every write is
 * dropped, matching the rest of this store: a conversation opened before a player's data has
 * attached remembers nothing rather than taking the render down. The component is attached when the
 * player connects, the one moment the engine hands anybody a holder.
 *
 * <p>World-thread, like every component read.
 */
public final class ZigProgressDialogueStore implements DialogueMemories.PersistentStore {

    /** The single instance the wiring root installs; it holds no state of its own. */
    public static final ZigProgressDialogueStore INSTANCE = new ZigProgressDialogueStore();

    private ZigProgressDialogueStore() {
    }

    @Override
    @Nullable
    public DialogueFlagStore forPlayer(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef) {
        ZigProgressComponent component = componentOf(store, ref);
        return component == null ? null : new ComponentView(component);
    }

    @Nullable
    private static ZigProgressComponent componentOf(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        try {
            return ZigProgressComponent.TYPE == null || !ref.isValid()
                    ? null : store.getComponent(ref, ZigProgressComponent.TYPE);
        } catch (Throwable t) {
            SafeLog.fine("[dialogue] no progress component to remember on: " + t);
            return null;
        }
    }

    /** One player's live component, answered in the dialogue engine's own vocabulary. */
    private record ComponentView(@Nonnull ZigProgressComponent component)
            implements DialogueFlagStore {

        @Override
        public boolean has(@Nonnull String flag) {
            return component.hasDialogueMemory(flag);
        }

        @Override
        public void set(@Nonnull String flag) {
            component.setDialogueMemory(flag);
        }

        @Override
        public void clear(@Nonnull String flag) {
            component.clearDialogueMemory(flag);
        }

        @Override
        public void clearWithPrefix(@Nonnull String prefix) {
            component.clearDialogueMemoriesWithPrefix(prefix);
        }

        @Override
        public void clearAll() {
            component.clearDialogueMemories();
        }
    }
}
