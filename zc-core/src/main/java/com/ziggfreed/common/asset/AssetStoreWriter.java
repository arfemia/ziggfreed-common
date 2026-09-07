package com.ziggfreed.common.asset;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.RawAsset;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ONE way this family writes an engine asset store after boot. Every store mutator ({@code
 * loadAssets}, {@code loadBuffersWithKeys}, {@code loadAssetsFromPaths}, {@code
 * loadAssetsWithReferences}) funnels into {@code AssetStore.loadAssets0}, which takes the
 * process-wide {@link AssetRegistry#ASSET_LOCK} WRITE lock and dispatches {@code
 * LoadedAssetsEvent} inside it. That lock is shared by every store in the JVM, and {@code
 * World.tick} holds its READ lock around the whole tick body, including both {@code
 * consumeTaskQueue()} drains, so anything dispatched through {@code world.execute(...)}, every
 * {@code AbstractPlayerCommand} body and every custom page handler runs holding it. A {@link
 * ReentrantReadWriteLock} never upgrades: a thread holding the read lock that asks for the write
 * lock waits for every reader to release, itself included, and the world thread wedges forever.
 * Boot is safe only because the asset-load pass runs before any world ticks; the engine's own
 * runtime hot reload is safe because it writes from the {@code PathWatcher} daemon thread, which
 * holds no read lock. This class mirrors that pattern instead of asking every call site to guess.
 *
 * <p><b>Policy.</b> {@link #write} tests the calling thread with {@link #callerHoldsAssetReadLock}:
 * with no read hold the write runs INLINE, synchronously, exactly as a direct call would (boot
 * ordering and every {@code LoadedAssetsEvent} listener's timing are unchanged; a thread that
 * holds the WRITE lock, as the boot loader does while it fires those listeners, re-enters it the
 * same way). With a read hold the write is handed to one named daemon thread ({@link
 * #THREAD_NAME}) and the caller gets {@link Outcome#DEFERRED} back at once, never a wait: the
 * writer cannot take the write lock until the current tick ends, so a caller that blocked on it
 * would deadlock just as hard. Writes are applied in submission order on that one thread.
 *
 * <p><b>Reading the result.</b> A caller that publishes an id the write creates (a wrapper root,
 * a stat, an effect) must do so only once the write has landed: chain the follow-up on {@link
 * Result#completion()} rather than assuming the asset exists on return, and treat {@link
 * Outcome#DEFERRED} as "not yet" for anything needed THIS tick. Never wait on the completion from
 * a thread holding the read lock.
 *
 * <p>{@link #offThread} runs a whole job on the writer thread unconditionally, for a pass that
 * makes many writes and must not tick-hop between them (a catalog re-fold from a command). A
 * {@link #write} made from inside such a job sees no read hold and runs inline, so the job keeps
 * its own ordering.
 *
 * <p>The MMO's {@code RepoHygieneTest} fails the build on a store mutator called outside this
 * file, so a new write shape becomes another typed convenience here rather than a raw call.
 */
public final class AssetStoreWriter {

    /** How a write request was handled. */
    public enum Outcome {
        /** The write ran on the calling thread and has finished. */
        RAN_INLINE,
        /** The calling thread holds the asset read lock; the write is queued on the writer thread. */
        DEFERRED,
        /** The write could not run: it threw inline, or no writer thread was available. */
        FAILED
    }

    /**
     * The outcome plus a future that completes when the write has landed (already complete for
     * an inline write, exceptionally for a failed one).
     */
    public record Result(@Nonnull Outcome outcome, @Nonnull CompletableFuture<Void> completion) {

        public boolean ranInline() {
            return outcome == Outcome.RAN_INLINE;
        }

        public boolean deferred() {
            return outcome == Outcome.DEFERRED;
        }

        public boolean failed() {
            return outcome == Outcome.FAILED;
        }
    }

    /** The writer thread's name, in the family's {@code ziggfreed-<job>} daemon convention. */
    public static final String THREAD_NAME = "ziggfreed-asset-writer";

    private static final Object EXECUTOR_LOCK = new Object();
    /** Created on the first deferral, so a JVM that never defers never starts a thread. */
    @Nullable
    private static ExecutorService executor;
    private static boolean shutDown;
    private static final AtomicBoolean LOCK_SHAPE_WARNED = new AtomicBoolean();

    private AssetStoreWriter() {
    }

    /**
     * Whether the calling thread holds {@link AssetRegistry#ASSET_LOCK}'s read lock, which on a
     * live server means "inside a ticking world". Exact for the {@link ReentrantReadWriteLock}
     * the engine declares; should the engine ever swap the lock type, the answer degrades to
     * {@code true} (defer everything) with one warning, since a wrong {@code false} would wedge
     * the server and a wrong {@code true} only delays a write by a tick.
     */
    public static boolean callerHoldsAssetReadLock() {
        ReadWriteLock lock = AssetRegistry.ASSET_LOCK;
        if (lock instanceof ReentrantReadWriteLock reentrant) {
            return reentrant.getReadHoldCount() > 0;
        }
        if (LOCK_SHAPE_WARNED.compareAndSet(false, true)) {
            SafeLog.warn("[asset] AssetRegistry.ASSET_LOCK is a " + lock.getClass().getName()
                    + ", not the ReentrantReadWriteLock this writer can test a read hold on; every"
                    + " runtime asset write is deferred to the " + THREAD_NAME + " thread from here on");
        }
        return true;
    }

    /**
     * Run {@code write} now if this thread may take the asset write lock, else queue it on the
     * writer thread. Never throws: an inline failure is logged with its cause and reported as
     * {@link Outcome#FAILED}; a deferred failure is logged the same way and fails the completion.
     *
     * @param what  a short label for the log lines ("OnHit wrapper root MMO_OnHitRoot_x")
     * @param write the store mutation, nothing else (so a failure is attributed to the write)
     */
    @Nonnull
    public static Result write(@Nonnull String what, @Nonnull Runnable write) {
        if (!callerHoldsAssetReadLock()) {
            return runNow(what, write);
        }
        Result result = submit(what, write);
        if (result.deferred()) {
            SafeLog.info("[asset] " + what + ": this thread holds the asset read lock (a world"
                    + " mid-tick), so the write waits on the " + THREAD_NAME
                    + " thread for the tick to end");
        }
        return result;
    }

    /**
     * {@link #write} of {@code assets} into {@code store} under {@code packKey} through {@code
     * AssetStore.loadAssets}: an existing id is overwritten in place, a new one is appended.
     */
    @Nonnull
    public static <K, T extends JsonAssetWithMap<K, M>, M extends AssetMap<K, T>> Result writeAssets(
            @Nonnull String what, @Nonnull AssetStore<K, T, M> store, @Nonnull String packKey,
            @Nonnull List<T> assets) {
        return write(what, () -> store.loadAssets(packKey, assets));
    }

    /**
     * {@link #write} of in-memory JSON documents into {@code store} under {@code packKey} through
     * {@code AssetStore.loadBuffersWithKeys}, decoded by the store's own codec exactly as a file
     * would be, with the default update query and no forced full reload: an existing id is
     * overwritten in place, a new one is appended.
     */
    @Nonnull
    public static <K, T extends JsonAssetWithMap<K, M>, M extends AssetMap<K, T>> Result writeBuffers(
            @Nonnull String what, @Nonnull AssetStore<K, T, M> store, @Nonnull String packKey,
            @Nonnull List<RawAsset<K>> buffers) {
        return write(what, () -> store.loadBuffersWithKeys(packKey, buffers, AssetUpdateQuery.DEFAULT, false));
    }

    /**
     * Run {@code job} on the writer thread whatever thread calls this, so a pass that writes many
     * assets and publishes state between them (a catalog re-fold) neither wedges a world thread
     * nor tick-hops between its writes. The future completes when the job returns, exceptionally
     * when it throws (logged with its cause). Never wait on it from a world thread.
     */
    @Nonnull
    public static CompletableFuture<Void> offThread(@Nonnull String what, @Nonnull Runnable job) {
        return submit(what, job).completion();
    }

    /**
     * Stop the writer thread and drop whatever it had not written yet. Called once from the
     * library plugin's {@code shutdown()}; a write requested after this is reported as {@link
     * Outcome#FAILED} rather than starting a thread the server is no longer around to serve.
     */
    public static void shutdown() {
        ExecutorService toStop;
        synchronized (EXECUTOR_LOCK) {
            shutDown = true;
            toStop = executor;
            executor = null;
        }
        if (toStop == null) {
            return;
        }
        List<Runnable> dropped = toStop.shutdownNow();
        if (!dropped.isEmpty()) {
            SafeLog.info("[asset] " + dropped.size() + " queued asset write(s) dropped at shutdown");
        }
    }

    @Nonnull
    private static Result runNow(@Nonnull String what, @Nonnull Runnable write) {
        try {
            write.run();
            return new Result(Outcome.RAN_INLINE, CompletableFuture.completedFuture(null));
        } catch (Throwable t) {
            SafeLog.warn("[asset] " + what + " failed", t);
            return new Result(Outcome.FAILED, CompletableFuture.failedFuture(t));
        }
    }

    @Nonnull
    private static Result submit(@Nonnull String what, @Nonnull Runnable job) {
        ExecutorService exec = executor();
        if (exec == null) {
            IllegalStateException cause = new IllegalStateException("asset writer already shut down");
            SafeLog.warn("[asset] " + what + " dropped: " + cause.getMessage());
            return new Result(Outcome.FAILED, CompletableFuture.failedFuture(cause));
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                try {
                    job.run();
                    completion.complete(null);
                } catch (Throwable t) {
                    SafeLog.warn("[asset] " + what + " failed on the " + THREAD_NAME + " thread", t);
                    completion.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            SafeLog.warn("[asset] " + what + " dropped: " + e.getMessage());
            return new Result(Outcome.FAILED, CompletableFuture.failedFuture(e));
        }
        return new Result(Outcome.DEFERRED, completion);
    }

    @Nullable
    private static ExecutorService executor() {
        synchronized (EXECUTOR_LOCK) {
            if (shutDown) {
                return null;
            }
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, THREAD_NAME);
                    t.setDaemon(true);
                    return t;
                });
            }
            return executor;
        }
    }
}
