package com.ziggfreed.common.objectives.title;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.entity.title.PendingTitles;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.objectives.title.TitleUnlocks.Outcome;
import com.ziggfreed.common.util.SafeLog;

/**
 * Titles for another mod, in one place: grant and revoke by player id from any thread, online or not;
 * read what a player has earned from any thread, online or not; and name a title or a player with it.
 * Listen for changes with {@link ZigTitleChangedEvent}.
 *
 * <p><b>Writes.</b> {@link #grant} and {@link #revoke} reach an online player by a hop to their world
 * thread and write through {@link TitleUnlocks}, answering what it did. A player who is not online
 * gets the change queued ({@link PendingTitles}), answered {@link Outcome#QUEUED}, and made at their
 * next login before anything else the login does with titles; one who came online meanwhile is paid
 * at once. Changes queued for one player are made in the order they were queued, and a change for an
 * online player who still has queued ones waits behind them.
 *
 * <p>Every future these return completes. A change that throws part-way on the world thread is queued
 * and answered {@link Outcome#QUEUED}, so {@code QUEUED} can follow a write that half-landed; the queued
 * copy is made again, and a grant or revoke made twice changes nothing the second time. An answer that
 * waits on a world thread completes exceptionally with a {@link java.util.concurrent.TimeoutException}
 * after five seconds (a world that took the task and stopped before running it): the change may still
 * land later or never, and a caller may simply send it again, since writes are idempotent (a second
 * grant answers {@link Outcome#ALREADY_UNLOCKED}, a second revoke {@link Outcome#NOT_UNLOCKED}).
 *
 * <p><b>Reads</b> come from the process-wide record ({@link ActiveTitles}): seeded at connect,
 * refreshed by every change, kept across a leave and a restart. A player the record has never seen
 * reads as having no titles.
 */
public final class ZigTitles {

    /** How long an answer waits on a player's world thread before it completes with a timeout. */
    private static final long WORLD_ANSWER_SECONDS = 5L;

    private ZigTitles() {
    }

    /**
     * Unlock {@code titleId} for {@code playerId}. Any thread. Answers {@link Outcome#UNLOCKED} or
     * {@link Outcome#ALREADY_UNLOCKED} for an online player (on their world thread),
     * {@link Outcome#QUEUED} for one who is not, {@link Outcome#REFUSED} at once for a blank id or one
     * carrying a reserved character, and {@link Outcome#NO_RECORD} for an online player without a record.
     * An online player's answer times out after five seconds, as the class says.
     */
    @Nonnull
    public static CompletableFuture<Outcome> grant(@Nonnull UUID playerId, @Nullable String titleId) {
        return change(playerId, titleId, PendingTitles.Kind.GRANT);
    }

    /**
     * Take {@code titleId} away from {@code playerId} (and stop showing it). Any thread. Answers
     * {@link Outcome#REVOKED} or {@link Outcome#NOT_UNLOCKED} for an online player, and otherwise as
     * {@link #grant} does.
     */
    @Nonnull
    public static CompletableFuture<Outcome> revoke(@Nonnull UUID playerId, @Nullable String titleId) {
        return change(playerId, titleId, PendingTitles.Kind.REVOKE);
    }

    /** Every title {@code playerId} has earned, lower-cased and sorted, online or not; empty for none. */
    @Nonnull
    public static Set<String> earned(@Nullable UUID playerId) {
        return ActiveTitles.earned(playerId);
    }

    /** What {@code titleId} is called, as a client-resolved {@link Message}. */
    @Nonnull
    public static Message nameOf(@Nonnull String titleId) {
        return TitleText.nameOf(titleId);
    }

    /**
     * {@code playerName} with the title {@code playerId} shows placed around it, when that title is on
     * offer; the plain name otherwise. The name is data, never translated. Any thread.
     */
    @Nonnull
    public static Message display(@Nonnull UUID playerId, @Nonnull String playerName) {
        Message decorated = TitleDisplay.decorate(playerId, playerName);
        return decorated != null ? decorated : Msg.raw(playerName);
    }

    /** {@code playerName} with {@code titleId} placed around it, by the title's display line. */
    @Nonnull
    public static Message display(@Nonnull String titleId, @Nonnull String playerName) {
        return TitleText.display(titleId, TitleConfig.getInstance().resolve(titleId), playerName);
    }

    // ==================== the writes ====================

    @Nonnull
    private static CompletableFuture<Outcome> change(@Nonnull UUID playerId, @Nullable String titleId,
            @Nonnull PendingTitles.Kind kind) {
        String id = TitleUnlocks.usable(titleId);
        if (id == null) {
            return CompletableFuture.completedFuture(Outcome.REFUSED);
        }
        Ref<EntityStore> ref = PlayerAccess.liveRef(playerId);
        if (ref != null) {
            CompletableFuture<Outcome> answer = new CompletableFuture<>();
            try {
                ref.getStore().getExternalData().getWorld().execute(() -> answerOnWorldThread(answer, playerId, ref,
                        id, kind));
                // A world that took the task but stops before running it would leave the answer waiting.
                return answer.orTimeout(WORLD_ANSWER_SECONDS, TimeUnit.SECONDS);
            } catch (Throwable t) {
                // Their world takes no more tasks (it is stopping): the queue holds it for the next login.
                SafeLog.fine("[title] could not reach a player's world, so the change is queued: " + t.getMessage());
            }
        }
        return CompletableFuture.completedFuture(queue(playerId, id, kind));
    }

    /** The world task: whatever throws, {@code answer} is completed. */
    private static void answerOnWorldThread(@Nonnull CompletableFuture<Outcome> answer, @Nonnull UUID playerId,
            @Nonnull Ref<EntityStore> ref, @Nonnull String id, @Nonnull PendingTitles.Kind kind) {
        Outcome outcome = Outcome.REFUSED;
        try {
            outcome = onWorldThread(playerId, ref, id, kind);
        } catch (Throwable t) {
            SafeLog.warn("[title] the " + kind + " of '" + id + "' could not be answered", t);
        } finally {
            answer.complete(outcome);
        }
    }

    /**
     * World thread: pay anything already queued for the player first (so changes land in the order they
     * were made), then make this one. A player who left on the way is queued instead.
     */
    @Nonnull
    private static Outcome onWorldThread(@Nonnull UUID playerId, @Nonnull Ref<EntityStore> ref, @Nonnull String id,
            @Nonnull PendingTitles.Kind kind) {
        try {
            if (!ref.isValid()) {
                return queue(playerId, id, kind);
            }
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return queue(playerId, id, kind);
            }
            TitleQueue.payOnWorldThread(ref);
            return kind == PendingTitles.Kind.GRANT
                    ? TitleUnlocks.unlock(store, ref, playerRef, id)
                    : TitleUnlocks.revoke(store, ref, playerRef, id);
        } catch (Throwable t) {
            SafeLog.warn("[title] the " + kind + " of '" + id + "' failed", t);
            return queue(playerId, id, kind);
        }
    }

    /**
     * Queue the change, then pay it at once if the player is online now after all. Never throws:
     * {@link Outcome#REFUSED} when nothing was queued, {@link Outcome#QUEUED} once it was, whatever the
     * payout then does.
     */
    @Nonnull
    private static Outcome queue(@Nonnull UUID playerId, @Nonnull String id, @Nonnull PendingTitles.Kind kind) {
        try {
            if (!PendingTitles.add(playerId, kind, id)) {
                return Outcome.REFUSED;
            }
        } catch (Throwable t) {
            SafeLog.warn("[title] the " + kind + " of '" + id + "' could not be queued", t);
            return Outcome.REFUSED;
        }
        try {
            TitleQueue.payIfOnline(playerId);
        } catch (Throwable t) {
            SafeLog.fine("[title] a queued title change waits for the next login: " + t.getMessage());
        }
        return Outcome.QUEUED;
    }
}
