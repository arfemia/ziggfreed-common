package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetRegistry;

/**
 * The deferral decision, against the engine's real {@link AssetRegistry#ASSET_LOCK}. Every write
 * here stands in for an asset-store mutation by taking the lock's WRITE side the way {@code
 * AssetStore.loadAssets0} does, so a wrong "run it here" answer would deadlock the test thread the
 * same way it deadlocks a world thread. The assertions are about the mechanism (which thread ran
 * the write, whether the caller waited, whether the completion tells the truth), never about a
 * number.
 */
class AssetStoreWriterTest {

    private static final long WAIT_SECONDS = 10;

    /** A stand-in store write: takes the asset write lock, records the thread, releases. */
    private static Runnable storeWrite(AtomicReference<Thread> ranOn) {
        return () -> {
            Lock write = AssetRegistry.ASSET_LOCK.writeLock();
            write.lock();
            try {
                ranOn.set(Thread.currentThread());
            } finally {
                write.unlock();
            }
        };
    }

    @Nested
    class WithoutAReadHold {

        @Test
        void theWriteRunsOnTheCallingThreadAndIsFinishedOnReturn() {
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            AssetStoreWriter.Result result = AssetStoreWriter.write("inline probe", storeWrite(ranOn));
            assertTrue(result.ranInline(), "no read hold: the write runs inline");
            assertSame(Thread.currentThread(), ranOn.get(), "an inline write runs on the caller's thread");
            assertTrue(result.completion().isDone(), "an inline write's completion is already complete");
            assertFalse(result.completion().isCompletedExceptionally());
        }

        @Test
        void aThreadHoldingTheWriteLockStillRunsInline() {
            // The boot loader holds the WRITE lock while it fires LoadedAssetsEvent, and a
            // listener's nested write must re-enter it synchronously, or boot ordering changes.
            Lock write = AssetRegistry.ASSET_LOCK.writeLock();
            write.lock();
            try {
                AtomicReference<Thread> ranOn = new AtomicReference<>();
                AssetStoreWriter.Result result = AssetStoreWriter.write("nested boot write", storeWrite(ranOn));
                assertTrue(result.ranInline(), "a write-lock holder re-enters, it never defers");
                assertSame(Thread.currentThread(), ranOn.get());
            } finally {
                write.unlock();
            }
        }

        @Test
        void aWriteThatThrowsIsReportedFailedWithItsCause() {
            IllegalStateException boom = new IllegalStateException("decode failed");
            AssetStoreWriter.Result result = AssetStoreWriter.write("failing write", () -> {
                throw boom;
            });
            assertTrue(result.failed());
            assertTrue(result.completion().isCompletedExceptionally());
            ExecutionException wrapped = assertThrows(ExecutionException.class, () -> result.completion().get());
            assertSame(boom, wrapped.getCause());
        }
    }

    @Nested
    class WithAReadHold {

        @Test
        void theWriteIsDeferredAndTheCallerNeverWaits() throws Exception {
            Lock read = AssetRegistry.ASSET_LOCK.readLock();
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            AssetStoreWriter.Result result;
            read.lock();
            try {
                // While this thread holds the read lock the stand-in write cannot take the write
                // lock, so if write() had waited on it, this call would never return.
                result = AssetStoreWriter.write("deferred probe", storeWrite(ranOn));
                assertTrue(result.deferred(), "a read holder is inside a ticking world: defer");
                assertFalse(result.completion().isDone(), "the write cannot have landed while the reader still holds");
                assertNull(ranOn.get(), "nothing ran on any thread yet");
            } finally {
                read.unlock();
            }
            result.completion().get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotEquals(Thread.currentThread(), ranOn.get(), "the deferred write ran on another thread");
            assertEquals(AssetStoreWriter.THREAD_NAME, ranOn.get().getName());
        }

        @Test
        void aDeferredWriteThatThrowsFailsItsCompletion() throws Exception {
            Lock read = AssetRegistry.ASSET_LOCK.readLock();
            IllegalStateException boom = new IllegalStateException("decode failed later");
            AssetStoreWriter.Result result;
            read.lock();
            try {
                result = AssetStoreWriter.write("deferred failing write", () -> {
                    throw boom;
                });
                assertTrue(result.deferred());
            } finally {
                read.unlock();
            }
            ExecutionException wrapped = assertThrows(ExecutionException.class,
                    () -> result.completion().get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertSame(boom, wrapped.getCause());
        }

        @Test
        void callerHoldsAssetReadLockTracksTheHold() {
            assertFalse(AssetStoreWriter.callerHoldsAssetReadLock());
            Lock read = AssetRegistry.ASSET_LOCK.readLock();
            read.lock();
            try {
                assertTrue(AssetStoreWriter.callerHoldsAssetReadLock());
            } finally {
                read.unlock();
            }
            assertFalse(AssetStoreWriter.callerHoldsAssetReadLock());
        }
    }

    @Nested
    class OffThread {

        @Test
        void theJobRunsOnTheWriterThreadAndItsOwnWritesRunInline() throws Exception {
            AtomicReference<Thread> jobRanOn = new AtomicReference<>();
            AtomicReference<AssetStoreWriter.Result> nested = new AtomicReference<>();
            AtomicReference<Thread> nestedRanOn = new AtomicReference<>();
            CompletableFuture<Void> done = AssetStoreWriter.offThread("re-fold", () -> {
                jobRanOn.set(Thread.currentThread());
                nested.set(AssetStoreWriter.write("write inside the job", storeWrite(nestedRanOn)));
            });
            done.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(AssetStoreWriter.THREAD_NAME, jobRanOn.get().getName());
            assertTrue(nested.get().ranInline(), "a write made from the writer thread does not re-queue itself");
            assertSame(jobRanOn.get(), nestedRanOn.get(), "it ran right there, in order with the job");
        }

        @Test
        void aJobThatThrowsFailsTheFuture() {
            IllegalStateException boom = new IllegalStateException("fold blew up");
            CompletableFuture<Void> done = AssetStoreWriter.offThread("broken re-fold", () -> {
                throw boom;
            });
            ExecutionException wrapped = assertThrows(ExecutionException.class,
                    () -> done.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertSame(boom, wrapped.getCause());
        }
    }
}
