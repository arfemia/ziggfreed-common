package com.ziggfreed.common.dialogue;

import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.state.DialogueFlagStore;

/**
 * A server-free {@link DialogueExecContext} for the pure engine tests: a dialogue plus an
 * in-memory state store. The engine-handle accessors throw, which is exactly the "world cannot be
 * read" path the world-scope resolution is guarded for, so a world-scoped Once or memory resolves
 * to no key here - the off-world case the tests assert directly.
 *
 * <p>Public, and so are {@link Flags} + {@link #state()} + the single-argument constructor:
 * {@code state.DialogueMemoriesTest} lives beside its subject in a different test package and
 * reaches back here for this shared plumbing. Widening a TEST helper across a test-package boundary
 * is not the same as widening production code; the two-argument constructor and
 * {@link #talkingTo(String)} are unused outside this package and stay as they were.
 */
public final class TestDialogueContext implements DialogueExecContext {

    /** The consumer-side state store, plus the prefix clear a quest reset performs. */
    public static final class Flags implements DialogueFlagStore {

        public final Set<String> keys = new LinkedHashSet<>();

        @Override public boolean has(@Nonnull String flag) { return keys.contains(flag); }

        @Override public void set(@Nonnull String flag) { keys.add(flag); }

        @Override public void clear(@Nonnull String flag) { keys.remove(flag); }

        /** What a consumer's quest reset does: drop everything filed under a leading prefix. */
        public void clearPrefix(@Nonnull String prefix) {
            keys.removeIf(key -> key.startsWith(prefix));
        }
    }

    private final NpcDialogue dialogue;
    private final Flags flags;
    @Nullable private String contextId;

    public TestDialogueContext(@Nonnull NpcDialogue dialogue) {
        this(dialogue, new Flags());
    }

    TestDialogueContext(@Nonnull NpcDialogue dialogue, @Nonnull Flags flags) {
        this.dialogue = dialogue;
        this.flags = flags;
    }

    @Nonnull public Flags state() { return flags; }

    /** Stand in for the character being talked to, which is what an alias answer set is asked about. */
    @Nonnull TestDialogueContext talkingTo(@Nullable String contextId) {
        this.contextId = contextId;
        return this;
    }

    @Override @Nonnull public NpcDialogue dialogue() { return dialogue; }

    @Override @Nonnull public String nodeId() { return ""; }

    @Override public int optionIndex() { return -1; }

    @Override @Nonnull public DialogueFlagStore flags() { return flags; }

    @Override @Nullable public String contextId() { return contextId; }

    @Override @Nonnull public Store<EntityStore> store() {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override @Nonnull public Ref<EntityStore> ref() {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override @Nonnull public PlayerRef playerRef() {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override @Nonnull public Player player() {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override @Nullable public <T> T payload(@Nonnull Class<T> type) { return null; }
}
