package com.ziggfreed.common.dialogue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * THE dialogue-state store: one surface every conversation reads and writes through, routing each
 * key to the backend the AUTHOR declared for it.
 *
 * <h2>Why the lifetime is declared per memory rather than chosen per server</h2>
 *
 * <p>A server-wide switch would decide for every mod at once, and mods share a server: a minigame
 * folded into an MMO as a dungeon runs beside it, so one install picking "session" would clobber
 * the other's persistent state and picking "persistent" would leave the minigame remembering a
 * round that ended weeks ago. So the choice is written where the state is described - beside
 * {@code Where}, {@code ResetWithQuest} and {@code Shared} on the {@code Memories} declaration -
 * and this class reads it off the key.
 *
 * <p><b>Unauthored means PERSISTENT</b>, which is the safe direction. A memory that outlives a
 * restart is a cosmetic surprise; one that silently vanishes breaks an authored
 * {@code ResetWithQuest} chain, and nothing would say so.
 *
 * <h2>The two backends, and who installs which</h2>
 *
 * <ul>
 *   <li>{@link InMemoryDialogueFlagStore} is the SESSION backend and ships in this module;</li>
 *   <li>the PERSISTENT backend cannot: it is kept on the shared progress component, which lives in
 *       the module that depends on THIS one. So it is a {@link PersistentStore} seam the wiring
 *       root fills, which is exactly what that root exists for.</li>
 * </ul>
 *
 * <p>With no persistent backend installed at all, a persistent key falls back to the session one
 * with a single warning. That degrades a restart-surviving memory to a login-surviving one rather
 * than dropping it silently, and the warning names the omission - the shape a unit JVM with no
 * server anywhere near it ends up in.
 *
 * <h2>Clearing is this layer's job, not a consumer's</h2>
 *
 * <p>{@link #forgetQuest} is the whole of what an authored {@code ResetWithQuest} promises. It
 * lives here because this is the only layer that knows BOTH the declaration and the storage: while
 * a consumer implemented the clearing, the declared lifetime was a promise exactly one consumer
 * kept, and nothing would have told any other that its authors' memories were never being reset.
 *
 * <p>The quest engine calls it ITSELF, through the reset hook the progression module declares and
 * the wiring root fills with {@link #forgetQuest(Subject, String)} - so a quest abandoned, a
 * repeatable coming round, or a bounty re-offered forgets what its conversations remembered on
 * every server, whoever wrote the quest.
 *
 * <p>There are three clears and they mean three different things, which is the distinction to hold
 * on to: {@link #forgetQuest} is ONE quest's state, {@link #forgetAllQuests} is every quest's state
 * and nothing else, and {@link #forgetAll} is genuinely everything. A quest reset reaches for one of
 * the first two; only somebody asking to start every conversation over wants the third.
 *
 * <p>World-thread, like everything that reaches a component through a {@code Store}.
 */
public final class DialogueMemories {

    /**
     * Where the PERSISTENT half of a player's dialogue state is kept. Filled once by the wiring
     * root; a consumer never implements it, and never implements {@link DialogueFlagStore} either.
     */
    @FunctionalInterface
    public interface PersistentStore {

        /**
         * This player's persistent dialogue state, or null when there is nowhere to keep it (their
         * component has not been attached yet). A null reads every key as unset and drops writes,
         * which is what a conversation opened before a player's data loaded should do.
         */
        @Nullable
        DialogueFlagStore forPlayer(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref);
    }

    /**
     * What a {@link Subject}'s handle offers when it can reach the player's LIVE ENTITY: the three
     * things every read and write here needs.
     *
     * <p>An engine operation says who it is about with a {@code Subject}, whose handle is opaque by
     * design - nothing in the library ever learns a consumer's player representation. That is
     * exactly right until a memory has to be forgotten off the back of one, at which point somebody
     * has to be able to ask. So the handle declares what it can point at, this is the whole of the
     * question, and a handle that implements nothing still reads as "no live player here" rather
     * than as an error.
     *
     * <p>Every leaf is nullable: a subject built over persisted state alone (a unit test driving a
     * state machine, a maintenance pass with no world) has a real handle and no entity behind it.
     */
    public interface SubjectHandles {

        /** The entity store the player's components live in, or null when there is no live one. */
        @Nullable
        Store<EntityStore> store();

        /** The player's entity handle, or null when there is no live one. */
        @Nullable
        Ref<EntityStore> ref();

        /** The player reference, or null when there is no live one. */
        @Nullable
        PlayerRef playerRef();
    }

    @Nullable
    private static volatile PersistentStore persistent;

    /** One warning per process for a missing persistent backend; the render path repeats forever. */
    private static final AtomicBoolean WARNED_NO_PERSISTENT = new AtomicBoolean();

    private DialogueMemories() {
    }

    /** Install the persistent backend. Called once, from the wiring root's {@code setup()}. */
    public static void install(@Nonnull PersistentStore store) {
        persistent = store;
    }

    /** Forget the installed backend and every session key (a test reset, and shutdown). */
    public static void reset() {
        persistent = null;
        WARNED_NO_PERSISTENT.set(false);
        InMemoryDialogueFlagStore.reset();
    }

    /**
     * The store a conversation reads and writes through: one {@link DialogueFlagStore} that routes
     * each key by the lifetime its author declared. Built per render, and cheap - both halves are
     * views over state that already exists.
     */
    @Nonnull
    public static DialogueFlagStore storeFor(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        DialogueFlagStore session = sessionFor(store, ref);
        return new Routed(session, persistentFor(store, ref, session));
    }

    /**
     * Forget everything this player remembers that was filed under {@code questId} - what an
     * authored {@code ResetWithQuest} means, in both backends at once.
     *
     * <p>The caller says only that the quest was reset. Which keys that reaches, how the quest id
     * is spelled inside one, and whether a session-declared memory is filed somewhere else entirely
     * are all this layer's business.
     */
    public static void forgetQuest(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable String questId) {
        if (questId == null || questId.isBlank()) {
            return;
        }
        try {
            storeFor(store, ref).clearWithPrefix(DialogueStateKeys.questPrefix(questId));
        } catch (Throwable t) {
            SafeLog.warn("[dialogue] could not forget the memories filed under quest '"
                    + questId + "'", t);
        }
    }

    /**
     * The same thing, said with the vocabulary an ENGINE has in hand: a {@link Subject} and the
     * quest that was reset. This is the form a progression engine's reset hook is filled with, so
     * an authored {@code ResetWithQuest} holds for every consumer on the server rather than for
     * whichever one wrote a prefix clear of its own.
     *
     * <p>A subject whose handle cannot reach a live entity - a state machine driven with no world,
     * a player mid-teardown - has nowhere to read or write, so nothing happens and nothing is
     * reported: there is no memory to forget on a player who is not there.
     */
    public static void forgetQuest(@Nullable Subject subject, @Nullable String questId) {
        if (subject == null) {
            return;
        }
        SubjectHandles handles = subject.handleAs(SubjectHandles.class);
        if (handles == null) {
            return;
        }
        Store<EntityStore> store = handles.store();
        Ref<EntityStore> ref = handles.ref();
        if (store == null || ref == null) {
            return;
        }
        // No player check here: the store-and-ref form below resolves the player for itself and
        // no-ops when the entity is not one, so asking twice would only pay the read twice.
        forgetQuest(store, ref, questId);
    }

    /**
     * Forget every memory this player holds that SOME quest owns, in both backends, and leave every
     * other memory standing.
     *
     * <p>This is what an administrator wiping a player's whole quest slate reaches for. There is no
     * one quest id left to key on, but "every quest" is still a far narrower thing than "everything
     * a conversation remembers": a greeting a character remembers giving, a name a player told
     * somebody, a one-shot gift already taken are not quest data, and a quest reset that took them
     * too would be answering a question nobody asked. {@link #forgetAll} is the one that means all
     * of it, and it exists for the administrator who asks for exactly that.
     *
     * <p>The reach is the {@code q:} NAMESPACE rather than a sweep of the ids the player's quest
     * state happens to carry. A conversation can set a memory about a quest that player never took,
     * which files it under an id their quest state has no record of, so a per-id sweep walks
     * straight past it and leaves the reset half done. The namespace is where every one of them is,
     * known id or not.
     */
    public static void forgetAllQuests(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        try {
            storeFor(store, ref).clearWithPrefix(DialogueStateKeys.QUEST_NAMESPACE);
        } catch (Throwable t) {
            SafeLog.warn("[dialogue] could not forget this player's quest-scoped dialogue memories", t);
        }
    }

    /** Forget every dialogue memory this player holds, in both backends (an admin start-over). */
    public static void forgetAll(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            storeFor(store, ref).clearAll();
        } catch (Throwable t) {
            SafeLog.warn("[dialogue] could not forget this player's dialogue memories", t);
        }
    }

    /**
     * End this player's SESSION: drop everything declared {@code Session} and leave the persistent
     * half alone. The wiring root calls it on disconnect; a consumer whose own boundary is shorter
     * (a minigame round, an instance visit) calls it at that boundary too.
     */
    public static void forgetSession(@Nullable UUID playerId) {
        if (playerId != null) {
            InMemoryDialogueFlagStore.forgetPlayer(playerId);
        }
    }

    /**
     * The SESSION half for the player behind this entity. The one place this class reads a
     * {@link PlayerRef}, so the derive happens once per {@link #storeFor} call and the persistent
     * half is handed the result rather than repeating it.
     */
    @Nonnull
    private static DialogueFlagStore sessionFor(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
        // Not a player at all, or one mid-teardown with no uuid left: session state has nowhere to
        // live, so it reads unset rather than throwing.
        UUID id = playerRef == null ? null : playerRef.getUuid();
        return id == null ? DialogueFlagStore.NONE : InMemoryDialogueFlagStore.forPlayer(id);
    }

    @Nonnull
    private static DialogueFlagStore persistentFor(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull DialogueFlagStore sessionFallback) {
        PersistentStore source = persistentBackendOrWarn();
        if (source == null) {
            return sessionFallback;
        }
        DialogueFlagStore resolved = source.forPlayer(store, ref);
        return resolved == null ? DialogueFlagStore.NONE : resolved;
    }

    /**
     * The installed persistent backend, or null having REPORTED that there is none.
     *
     * <p>The decision is here rather than inline above so it can be driven without three live
     * entity handles: whether a declared lifetime is actually being honoured is a question about
     * the seam, not about any one player, and a check reachable only behind a live world is a check
     * nothing runs until a server is up.
     */
    @Nullable
    static PersistentStore persistentBackendOrWarn() {
        PersistentStore source = persistent;
        if (source == null) {
            warnNoPersistentOnce();
        }
        return source;
    }

    /**
     * Say once, out loud, that a lifetime this layer promises to honour is not being honoured.
     *
     * <p>This is the seam's own report on itself. A memory declared to outlive a restart quietly
     * becoming a login-lived one is invisible from every side: the dialogue file is correct, the
     * declaration is correct, and the player simply gets the first-visit beat again. Nothing but
     * this layer is in a position to notice, so it says so the first time it has to fall back,
     * rather than leaving the omission to be discovered as a bug report weeks later.
     *
     * @return true when this call is the one that reported it, false when it was already said
     */
    static boolean warnNoPersistentOnce() {
        if (!WARNED_NO_PERSISTENT.compareAndSet(false, true)) {
            return false;
        }
        SafeLog.warn("[dialogue] no persistent memory backend is installed, so every memory"
                + " lasts only as long as the player's session. A conversation's first-visit"
                + " beats and one-shot gifts will come back on their next login. The fill is"
                + " DialogueMemories.install(PersistentStore) from the wiring root's setup();"
                + " a unit JVM with no server anywhere near it is the one place this is expected.");
        return true;
    }

    /**
     * The router itself: {@link DialogueStateKeys#isSession} decides which backend a key belongs
     * to, and that is the whole decision. Both clears reach BOTH backends, because a lifetime is a
     * property of one memory rather than of the quest or the player being reset - so a quest owning
     * one persistent memory and one session memory has both forgotten when it is reset, which is
     * what the author wrote either way.
     *
     * <p>The prefix clear is the whole of what {@code ResetWithQuest} and the wider quest-namespace
     * clear are made of, and its correctness is entirely in the pairing above: a clear that reached
     * one backend and not the other is a memory outliving the quest it was declared to die with,
     * with nothing anywhere saying so. So the router is nameable from within this package and can
     * be driven over two ordinary stores, rather than being reachable only behind three live entity
     * handles that no correctness question here actually needs.
     */
    record Routed(@Nonnull DialogueFlagStore session, @Nonnull DialogueFlagStore persistent)
            implements DialogueFlagStore {

        @Override
        public boolean has(@Nonnull String flag) {
            return backendFor(flag).has(flag);
        }

        @Override
        public void set(@Nonnull String flag) {
            backendFor(flag).set(flag);
        }

        @Override
        public void clear(@Nonnull String flag) {
            backendFor(flag).clear(flag);
        }

        @Override
        public void clearWithPrefix(@Nonnull String prefix) {
            persistent.clearWithPrefix(prefix);
            session.clearWithPrefix(DialogueStateKeys.withSession(true, prefix));
        }

        @Override
        public void clearAll() {
            persistent.clearAll();
            session.clearAll();
        }

        @Nonnull
        private DialogueFlagStore backendFor(@Nonnull String flag) {
            return DialogueStateKeys.isSession(flag) ? session : persistent;
        }
    }
}
