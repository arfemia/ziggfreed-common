package com.ziggfreed.common.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.util.io.FileUtil;

/**
 * The one way the library keeps a JSON record file: a store's memory answers every read, and its file is
 * written whole, atomically, through the engine's {@code FileUtil.writeStringAtomic}, which keeps the copy
 * before it as {@code .bak}. Every store with a file of its own goes through here, so they cannot drift.
 *
 * <p><b>The version.</b> Every write carries {@value #VERSION_KEY}, the shape the store writes. A file
 * written before its store kept one reads as {@link #UNVERSIONED}, unchanged. A file declaring a NEWER
 * version than the store reads is refused whole: memory starts empty, and the file is left unread and
 * unwritten for the build that reads it, with one warning.
 *
 * <p><b>A torn file.</b> A missing, empty or unreadable file reads the {@code .bak} instead (one warning; an
 * unreadable one is first copied aside as {@code .unreadable}), and the first write after that keeps the good
 * {@code .bak} rather than backing up the torn file over it.
 * With nothing readable, a store either refuses the file ({@link Builder#refuseUnreadable()}: left whole and
 * unwritten this run, for a record of what a player is owed) or sets it aside as {@code .unreadable}
 * (never over one already there) and starts empty, so nothing on disk is lost and the store keeps saving.
 * A missing or empty file with no {@code .bak} is a fresh start, not a refusal.
 *
 * <p><b>When a change is written.</b> {@link #changed} marks memory dirty and asks the flusher
 * {@link #persistTo} was given for one write (once per burst); with no flusher, the store writes when it
 * calls {@link #flush} or {@link #writeNow}. A write the engine cannot make (a plain unit JVM, where
 * {@code FileUtil} cannot load on Update 7) is swallowed with one warning and stays dirty, so memory keeps
 * answering and the next write tries again. Writes are serialized.
 */
public final class RecordFile {

    /** The leaf every record file's version sits under. */
    public static final String VERSION_KEY = "version";

    /** What a file written before its store kept a version reads as. */
    public static final int UNVERSIONED = 0;

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    /** What the file holds: the owner's memory, read and written through here. */
    public interface Body {

        /** Memory as the file's document, without the version leaf (the writer adds it). Called under the write lock. */
        @Nonnull
        JsonObject write();

        /**
         * Replace memory with what {@code doc} holds, written at {@code version} ({@link #UNVERSIONED} for a file
         * from before its store kept one). Throw when it cannot be read; memory is then emptied.
         */
        void read(@Nonnull JsonObject doc, int version) throws Exception;

        /** Empty memory. */
        void forget();
    }

    /** How one store's file is kept; {@link #build} makes the writer. */
    public static final class Builder {

        @Nonnull
        private final String tag;
        @Nonnull
        private final String what;
        @Nonnull
        private final Body body;
        private int version = 1;
        @Nonnull
        private String lostWrite = "a restart may lose the latest change";
        private boolean refuseUnreadable;
        private boolean compact;

        private Builder(@Nonnull String tag, @Nonnull String what, @Nonnull Body body) {
            this.tag = tag;
            this.what = what;
            this.body = body;
        }

        /** The shape the store writes (1 unless written); a file declaring a higher one is refused. */
        @Nonnull
        public Builder version(int version) {
            this.version = Math.max(1, version);
            return this;
        }

        /** What a lost write costs, as a log line says it ("a restart may lose a Claim"). */
        @Nonnull
        public Builder lostWrite(@Nonnull String lostWrite) {
            this.lostWrite = lostWrite;
            return this;
        }

        /**
         * With nothing readable, leave the file whole and unwritten this run instead of setting it aside and
         * starting over: for a record of what a player is owed, which an admin repairs by hand.
         */
        @Nonnull
        public Builder refuseUnreadable() {
            this.refuseUnreadable = true;
            return this;
        }

        /** Write compact JSON instead of pretty: a file written often and never read by hand. */
        @Nonnull
        public Builder compact() {
            this.compact = true;
            return this;
        }

        @Nonnull
        public RecordFile build() {
            return new RecordFile(this);
        }
    }

    /** What reading one file found. */
    private enum Read {
        /** Read whole into memory. */
        READ,
        /** No file there. */
        MISSING,
        /** A file with nothing in it. */
        EMPTY,
        /** Written by a newer build. */
        NEWER,
        /** There, but it would not read. */
        UNREADABLE
    }

    @Nonnull
    private final String tag;
    @Nonnull
    private final String what;
    @Nonnull
    private final String lostWrite;
    @Nonnull
    private final Body body;
    private final int version;
    private final boolean refuseUnreadable;
    @Nonnull
    private final Gson gson;

    /** A change memory holds that the file does not yet. */
    private final AtomicBoolean dirty = new AtomicBoolean();

    /** A flush is already waiting on the flusher, so a change needs to ask for no other. */
    private final AtomicBoolean flushAsked = new AtomicBoolean();

    private final Object writeLock = new Object();

    @Nullable
    private volatile Path file;

    @Nullable
    private volatile Executor flusher;

    /** True once a newer or unreadable file was refused: it is never overwritten this run. */
    private volatile boolean refused;

    /** False until a good file stands again after reading the {@code .bak}: the next write keeps the {@code .bak}. */
    private boolean backupNext = true;

    /** The version the last read found, for the store's own diagnostics. */
    private volatile int readVersion = UNVERSIONED;

    /** The version a refused newer file declared, for its log line. */
    private int newerVersion;

    private RecordFile(@Nonnull Builder builder) {
        this.tag = builder.tag;
        this.what = builder.what;
        this.lostWrite = builder.lostWrite;
        this.body = builder.body;
        this.version = builder.version;
        this.refuseUnreadable = builder.refuseUnreadable;
        this.gson = builder.compact ? COMPACT : PRETTY;
    }

    /**
     * A writer for one store's file.
     *
     * @param tag  the store's log prefix, without brackets ({@code "claims"})
     * @param what what the record holds, as a log line names it ("the Claims")
     * @param body the store's memory
     */
    @Nonnull
    public static Builder builder(@Nonnull String tag, @Nonnull String what, @Nonnull Body body) {
        return new Builder(tag, what, body);
    }

    /** The JSON shape every record file is written in, for a store's own document building. */
    @Nonnull
    public static Gson gson() {
        return PRETTY;
    }

    /**
     * Keep the record in {@code target} (null: in memory only) and read what it holds, replacing what memory
     * held. A change is then written a moment later through {@code flushOn} (null: only when {@link #flush} or
     * {@link #writeNow} is called). Never throws.
     */
    public void persistTo(@Nullable Path target, @Nullable Executor flushOn) {
        synchronized (writeLock) {
            file = target;
            flusher = flushOn;
            flushAsked.set(false);
            dirty.set(false);
            refused = false;
            backupNext = true;
            readVersion = UNVERSIONED;
            body.forget();
            if (target != null) {
                load(target);
            }
        }
    }

    /** Write the record to its file now, if anything changed since the last write. Any thread. */
    public void flush() {
        write();
    }

    /**
     * The one write behind {@link #flush} and {@link #writeNow}: true when the file now holds what memory holds
     * (written now, or nothing changed since the last write that landed); false in memory only, for a refused
     * file, or when the write failed (the change is kept for the next).
     */
    private boolean write() {
        synchronized (writeLock) {
            Path target = file;
            if (target == null || refused) {
                return false;
            }
            if (!dirty.getAndSet(false)) {
                return true;
            }
            try {
                String text = gson.toJson(versioned(body.write()));
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                FileUtil.writeStringAtomic(target, text, backupNext);
                backupNext = true;
                return true;
            } catch (Throwable t) {
                dirty.set(true);
                SafeLog.warn("[" + tag + "] could not write " + target + ", so " + lostWrite + ": " + t.getMessage());
                return false;
            }
        }
    }

    /** Memory changed: ask the flusher for one write, or write now when the flusher cannot take it. */
    public void changed() {
        dirty.set(true);
        Executor later = flusher;
        if (later == null || file == null || !flushAsked.compareAndSet(false, true)) {
            return;
        }
        try {
            later.execute(this::askedFlush);
        } catch (Throwable t) {
            // No flusher to wait on (a scheduler already shut down, a unit JVM): write it now instead.
            flushAsked.set(false);
            flush();
        }
    }

    /**
     * Memory changed and the file follows at once: the store whose every change is written before it returns. True
     * when the write landed; false in memory only, for a refused file, or when the write failed.
     */
    public boolean writeNow() {
        dirty.set(true);
        return write();
    }

    /** Memory was emptied on purpose (a test, or a restart's first step): nothing is owed to the file. */
    public void clean() {
        dirty.set(false);
    }

    /** True when this run refused the file (a newer version, or one this store will not overwrite unread). */
    public boolean refused() {
        return refused;
    }

    /** The file the record is kept in, or null in memory only. */
    @Nullable
    public Path file() {
        return file;
    }

    /** The version the last read found ({@link #UNVERSIONED} for none, or no file). */
    public int readVersion() {
        return readVersion;
    }

    /** The version this store writes. */
    public int version() {
        return version;
    }

    private void askedFlush() {
        flushAsked.set(false);
        flush();
    }

    /** {@code doc} with the version leaf first and the store's own leaves after it. */
    @Nonnull
    private JsonObject versioned(@Nonnull JsonObject doc) {
        JsonObject out = new JsonObject();
        out.addProperty(VERSION_KEY, version);
        for (Map.Entry<String, JsonElement> leaf : doc.entrySet()) {
            if (!VERSION_KEY.equals(leaf.getKey())) {
                out.add(leaf.getKey(), leaf.getValue());
            }
        }
        return out;
    }

    /** Read {@code target}, else the copy the atomic write kept before it, else start empty (see the class rules). */
    private void load(@Nonnull Path target) {
        Read main = read(target);
        if (main == Read.READ) {
            return;
        }
        if (main == Read.NEWER) {
            refuse(target, newerWhy());
            return;
        }
        Path backup = sibling(target, ".bak");
        Read copy = read(backup);
        if (copy == Read.READ) {
            if (main != Read.MISSING) {
                backupNext = false;
            }
            Path aside = main == Read.UNREADABLE ? keepAside(target) : null;
            SafeLog.warn("[" + tag + "] " + target + " is " + (main == Read.MISSING ? "missing" : "torn") + ", so "
                    + what + " come from " + backup.getFileName()
                    + (aside == null ? "" : " (the torn file is kept as " + aside.getFileName() + ")"));
            return;
        }
        if (copy == Read.NEWER) {
            refuse(backup, newerWhy());
            return;
        }
        if (main != Read.UNREADABLE) {
            return; // missing or empty, and no copy to read: a fresh start
        }
        if (refuseUnreadable) {
            refuse(target, "will not read");
            return;
        }
        setAside(target);
    }

    /** What reading {@code source} found; memory holds it only on {@link Read#READ}. */
    @Nonnull
    private Read read(@Nonnull Path source) {
        if (!Files.isRegularFile(source)) {
            return Read.MISSING;
        }
        try {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return Read.EMPTY;
            }
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                throw new IllegalStateException("the file is not a JSON object");
            }
            JsonObject doc = root.getAsJsonObject();
            JsonElement declared = doc.get(VERSION_KEY);
            int found = declared == null || declared.isJsonNull() ? UNVERSIONED : declared.getAsInt();
            if (found > version) {
                newerVersion = found;
                return Read.NEWER;
            }
            body.read(doc, found);
            readVersion = found;
            return Read.READ;
        } catch (Throwable t) {
            body.forget();
            SafeLog.warn("[" + tag + "] could not read " + source + ": " + t.getMessage());
            return Read.UNREADABLE;
        }
    }

    @Nonnull
    private String newerWhy() {
        return "declares version " + newerVersion + ", newer than the " + version + " this build reads";
    }

    private void refuse(@Nonnull Path source, @Nonnull String why) {
        refused = true;
        body.forget();
        SafeLog.warn("[" + tag + "] " + source + " " + why + ", so it is left unread and unwritten this run and "
                + what + " start empty");
    }

    /** Copy the unreadable file aside and start empty; a file that cannot be kept aside is never written over. */
    private void setAside(@Nonnull Path target) {
        backupNext = false;
        Path aside = keepAside(target);
        if (aside != null) {
            SafeLog.warn("[" + tag + "] neither " + target + " nor its backup reads, so it is kept as "
                    + aside.getFileName() + " and " + what + " start empty");
            return;
        }
        refused = true;
        SafeLog.warn("[" + tag + "] neither " + target + " nor its backup reads, and it could not be kept aside, so it"
                + " is left unwritten this run and " + what + " start empty");
    }

    /** A copy of {@code target} beside it as {@code .unreadable}, never over a copy already there; null when it failed. */
    @Nullable
    private Path keepAside(@Nonnull Path target) {
        Path aside = sibling(target, ".unreadable");
        if (Files.exists(aside)) {
            aside = sibling(target, ".unreadable-" + System.currentTimeMillis());
        }
        try {
            Files.copy(target, aside, StandardCopyOption.COPY_ATTRIBUTES);
            return aside;
        } catch (Throwable t) {
            SafeLog.warn("[" + tag + "] could not keep " + target + " aside as " + aside.getFileName() + ": "
                    + t.getMessage());
            return null;
        }
    }

    @Nonnull
    private static Path sibling(@Nonnull Path target, @Nonnull String suffix) {
        return target.resolveSibling(target.getFileName() + suffix);
    }
}
