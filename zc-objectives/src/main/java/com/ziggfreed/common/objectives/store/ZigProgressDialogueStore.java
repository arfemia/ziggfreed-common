package com.ziggfreed.common.objectives.store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.DialogueFlagStore;
import com.ziggfreed.common.dialogue.DialogueMemories;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;
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
 * <p><b>Every write here is a change to the player's PROGRESS state</b>, because that is the
 * component it lands on, so a remembered or forgotten memory reports through the same dirty fan-out
 * a quest or an achievement write does ({@link ProgressionDefaults#onProgressDirty}). A consumer
 * with its own persistence backend would otherwise see a conversation's memory as the one saved
 * thing nothing ever told it about, and every remembered beat would revert on the next hydrate.
 *
 * <p>World-thread, like every component read.
 */
public final class ZigProgressDialogueStore implements DialogueMemories.PersistentStore {

    /** The single instance {@code DialogueBootstrap} installs; it holds no state of its own. */
    public static final ZigProgressDialogueStore INSTANCE = new ZigProgressDialogueStore();

    private ZigProgressDialogueStore() {
    }

    @Override
    @Nullable
    public DialogueFlagStore forPlayer(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        ZigProgressComponent component = componentOf(store, ref);
        // Through the REGISTERED subject source, never this module's own builder: on a server where a
        // consumer registered its own, a memory write has to arrive carrying the same handle a quest
        // write does, or a backend reading its session off the subject hears only half the story.
        return component == null ? null
                : new ComponentView(component,
                        ProgressionRuntime.subjects().questSubject(store, ref));
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

    /**
     * One player's live component, answered in the dialogue engine's own vocabulary.
     *
     * <p>The subject travels with it purely so a WRITE can be reported to the dirty fan-out. It is
     * nullable because a player whose uuid cannot be read yet has no subject to build - the memory
     * write still lands on the component, and only the notification is skipped.
     */
    private record ComponentView(@Nonnull ZigProgressComponent component,
                                 @Nullable Subject subject) implements DialogueFlagStore {

        @Override
        public boolean has(@Nonnull String flag) {
            return component.hasDialogueMemory(flag);
        }

        @Override
        public void set(@Nonnull String flag) {
            component.setDialogueMemory(flag);
            changed();
        }

        @Override
        public void clear(@Nonnull String flag) {
            if (component.clearDialogueMemory(flag)) {
                changed();
            }
        }

        @Override
        public void clearWithPrefix(@Nonnull String prefix) {
            component.clearDialogueMemoriesWithPrefix(prefix);
            changed();
        }

        @Override
        public void clearAll() {
            component.clearDialogueMemories();
            changed();
        }

        /**
         * Tell every registered backend the player's saved state moved. Reads never call this.
         *
         * <p>The two bulk clears and {@link #set} report unconditionally, because neither the
         * component nor an idempotent set can say whether anything actually moved. A dirty
         * notification is idempotent, so the cost of an occasional redundant one is a backend
         * looking again at a player it already knew about.
         */
        private void changed() {
            if (subject != null) {
                ProgressionDefaults.fireProgressDirty(subject);
            }
        }
    }
}
