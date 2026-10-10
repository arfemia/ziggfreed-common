package com.ziggfreed.common.entity.title;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.util.io.FileUtil;
import com.ziggfreed.common.util.SafeLog;

/**
 * How a process-wide title record keeps its file, shared by {@link ActiveTitles} and
 * {@link PendingTitles} so the two files are kept the same way. Memory answers every read; a change
 * marks the record dirty and is written a moment later on the flusher {@link #persistTo} was given
 * (once per burst), through the engine's atomic write, which keeps the copy before it as
 * {@code .bak}. A missing or torn file reads that copy at the next start.
 *
 * <p>A write the engine cannot make (a plain unit JVM, where {@code FileUtil} cannot load on
 * Update 7) is swallowed with one warning and stays dirty, so memory keeps answering and the next
 * flush tries again. Writes to the file are serialized.
 */
final class RecordFile {

    /** The one JSON shape both files are written in. */
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** What the file holds: the owner's memory, read and written through here. */
    interface Body {

        /** Memory as the file's text. Called under the write lock. */
        @Nonnull
        String write();

        /** Replace memory with what {@code text} holds; throw when it cannot be read. */
        void read(@Nonnull String text) throws Exception;

        /** Empty memory. */
        void forget();
    }

    /** What the record holds, as a log line names it ("the shown titles"). */
    @Nonnull
    private final String what;

    /** What a lost write costs, as a log line says it. */
    @Nonnull
    private final String lostWrite;

    @Nonnull
    private final Body body;

    /** A change memory holds that the file does not yet. */
    private final AtomicBoolean dirty = new AtomicBoolean();

    /** A flush is already waiting on the flusher, so a change needs to ask for no other. */
    private final AtomicBoolean flushAsked = new AtomicBoolean();

    private final Object writeLock = new Object();

    @Nullable
    private volatile Path file;

    @Nullable
    private volatile Executor flusher;

    RecordFile(@Nonnull String what, @Nonnull String lostWrite, @Nonnull Body body) {
        this.what = what;
        this.lostWrite = lostWrite;
        this.body = body;
    }

    /**
     * Keep the record in {@code target} (null: in memory only) and read what it holds, replacing what
     * memory held. A change is then written a moment later through {@code flushOn} (null: only when
     * {@link #flush} is called).
     */
    void persistTo(@Nullable Path target, @Nullable Executor flushOn) {
        synchronized (writeLock) {
            file = target;
            flusher = flushOn;
            flushAsked.set(false);
            dirty.set(false);
            body.forget();
            if (target != null) {
                load(target);
            }
        }
    }

    /** Write the record to its file now, if anything changed since the last write. Any thread. */
    void flush() {
        synchronized (writeLock) {
            Path target = file;
            if (target == null || !dirty.getAndSet(false)) {
                return;
            }
            try {
                String text = body.write();
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                FileUtil.writeStringAtomic(target, text, true);
            } catch (Throwable t) {
                dirty.set(true);
                SafeLog.warn("[title] could not write " + target + ", so " + lostWrite + ": " + t.getMessage());
            }
        }
    }

    /** Memory changed: ask the flusher for one write, or write now when there is no flusher to ask. */
    void changed() {
        dirty.set(true);
        Executor later = flusher;
        if (later == null || file == null || !flushAsked.compareAndSet(false, true)) {
            return;
        }
        try {
            later.execute(this::askedFlush);
        } catch (Throwable t) {
            // No flusher to wait on (a scheduler already shut down): write it now instead.
            flushAsked.set(false);
            flush();
        }
    }

    /** Memory was emptied on purpose (a test, or a restart's first step): nothing is owed to the file. */
    void clean() {
        dirty.set(false);
    }

    private void askedFlush() {
        flushAsked.set(false);
        flush();
    }

    /** Read {@code target}, else the copy the atomic write kept before it, else start empty. */
    private void load(@Nonnull Path target) {
        if (read(target)) {
            return;
        }
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        if (read(backup)) {
            SafeLog.warn("[title] " + target + " is missing or torn, so " + what + " come from "
                    + backup.getFileName());
        }
    }

    /** True when {@code source} was there and read whole into memory. */
    private boolean read(@Nonnull Path source) {
        if (!Files.isRegularFile(source)) {
            return false;
        }
        try {
            body.read(Files.readString(source));
            return true;
        } catch (Throwable t) {
            body.forget();
            SafeLog.warn("[title] could not read " + source + ": " + t.getMessage());
            return false;
        }
    }
}
