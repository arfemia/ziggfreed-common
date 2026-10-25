package com.ziggfreed.common.content;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.ziggfreed.common.asset.AssetStoreWriter;

import org.bson.BsonDocument;

/**
 * The server content pack: the one place every in-game editor saves what an admin makes, an ordinary
 * folder content pack the server owns ({@code mods/ServerContent}, {@link ServerContentPack}), whose
 * files load live, load again at every start, and open in Hytale's Asset Editor, plus the words typed
 * for it ({@link ServerContentText}). A server running this library alone has all of it.
 *
 * <p><b>One writer.</b> {@link #write} and {@link #remove} never touch a store from the calling thread:
 * the whole save runs on the asset writer thread ({@link AssetStoreWriter#offThread}), because a page
 * handler or a command runs on a world thread that holds the engine's asset read lock, and a store
 * write there would wait on that same thread forever. A save is checked the way the Asset Editor checks
 * one (the store decodes it, with its own checks) before its file is written, then the store loads the
 * file as its file watcher would, so the store's own fold runs as for any pack file. Nothing here throws
 * into a caller: a refusal arrives on the result's {@link Result#completion()} as a {@link
 * ContentRefusedException}, its {@link ContentProblem} the sentence to show. Never wait on a completion
 * from a world thread or from the writer thread: publish on it.
 *
 * <p><b>Ids never move.</b> {@link #mintId} makes an id from the typed name once, at creation, unique in
 * its store; saved progress is filed under it, so an editor changes an asset's words, never its id.
 *
 * <p><b>Words are keys.</b> {@link #setText} saves English under a key ({@link #textKey} builds the
 * family's {@code <store>.<id>.<field>}) and says when it shows ({@link AppliesWhen}); an asset names the
 * registered key, {@link #storedKey}.
 */
public final class ServerContent {

    /**
     * What a save was given back: the asset id (or the registered text key), when the change reaches
     * players, and the future that completes when it has landed, or completes exceptionally with a
     * {@link ContentRefusedException} when it was refused.
     */
    public record Result(@Nonnull String id, @Nonnull AppliesWhen appliesWhen, @Nonnull CompletableFuture<Void> completion) {

        /** True once the save is known to be refused (at once for a bad id or document). */
        public boolean refused() {
            return completion.isCompletedExceptionally();
        }

        /** Why it was refused, once known; null while it is pending or when it landed. */
        @Nullable
        public ContentProblem problem() {
            return completion.state() == Future.State.FAILED ? ContentRefusedException.problemOf(completion.exceptionNow()) : null;
        }
    }

    /** Where the pack stands, for {@code /zigpack status} and an editor that must say why it cannot save. */
    public enum State {
        READY,
        READ_ONLY,
        DISABLED,
        STARTING,
        FAILED,
        UNAVAILABLE
    }

    /**
     * The pack at a glance. {@code fileWatcher} is whether the server runs its file watcher at all;
     * {@code watchedFolders} how many of the pack's store folders it covers this session (those there
     * when the pack loaded, and those a save made since). A store folder made by hand or in the Asset
     * Editor since the start is watched from the next start.
     */
    public record Status(@Nonnull State state, @Nonnull String packId, @Nonnull Path folder, int assetFiles,
            int texts, boolean fileWatcher, int watchedFolders) {
    }

    /** What an export wrote. */
    public record Export(@Nonnull Path zip, @Nonnull String packId, int files, int texts) {
    }

    /** Ids minted this session and not yet written, per store, lower-cased: two editors never get one id. */
    private static final Map<Class<?>, Set<String>> MINTED = new ConcurrentHashMap<>();

    private ServerContent() {
    }

    // ---------------------------------------------------------------- the pack

    /** The pack's id ({@code Group:Name}). */
    @Nonnull
    public static String packId() {
        return ServerContentPack.packId();
    }

    /** True when a save can land now. */
    public static boolean writable() {
        return ServerContentPack.writable() != null;
    }

    /** The pack at a glance. Never throws. */
    @Nonnull
    public static Status status() {
        AssetPack pack = ServerContentPack.loaded();
        Path folder = pack != null ? pack.getRoot() : ServerContentPack.folder().toAbsolutePath().normalize();
        boolean watcher;
        try {
            AssetModule am = AssetModule.get();
            watcher = am != null && am.getAssetMonitor() != null;
        } catch (Throwable t) {
            watcher = false;
        }
        return new Status(publicState(), packId(), folder, countAssetFiles(folder), ServerContentText.size(), watcher,
                watcher ? countWatchedFolders(folder) : 0);
    }

    /** How many of the pack's store folders the engine's file watcher covers this session. */
    private static int countWatchedFolders(@Nonnull Path root) {
        int watched = 0;
        try {
            for (AssetStore<?, ?, ?> store : AssetRegistry.getStoreMap().values()) {
                if (!filesInPack(store)) {
                    continue;
                }
                Path dir = root.resolve("Server").resolve(store.getPath()).toAbsolutePath().normalize();
                if (Files.isDirectory(dir) && ServerContentPack.watched(dir)) {
                    watched++;
                }
            }
        } catch (Throwable t) {
            return watched;
        }
        return watched;
    }

    @Nonnull
    private static State publicState() {
        return switch (ServerContentPack.state()) {
            case READY -> State.READY;
            case READ_ONLY -> State.READ_ONLY;
            case DISABLED -> State.DISABLED;
            case NOT_SET_UP, STARTING -> State.STARTING;
            case FAILED -> State.FAILED;
            case UNAVAILABLE -> State.UNAVAILABLE;
        };
    }

    // ---------------------------------------------------------------- ids

    /**
     * An {@code Is_Like_This} id for a new asset of {@code store}, made from {@code typedName}: free in
     * the store (whatever pack holds it), in this pack's files and among ids minted this session, with
     * the first free {@code _2}, {@code _3} suffix when the plain one is taken. Mint once, at creation;
     * the id never changes after.
     */
    @Nonnull
    public static <T extends JsonAssetWithMap<String, M>, M extends AssetMap<String, T>> String mintId(
            @Nonnull AssetStore<String, T, M> store, @Nullable String typedName) {
        Set<String> taken = new HashSet<>();
        try {
            for (String key : store.getAssetMap().getAssetMap().keySet()) {
                taken.add(key.toLowerCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {
            // A store that cannot list its keys mints against the pack's files and this session's ids.
        }
        AssetPack pack = ServerContentPack.loaded();
        if (pack != null) {
            taken.addAll(fileIds(storeDir(pack, store), extension(store)));
        }
        Set<String> minted = MINTED.computeIfAbsent(store.getAssetClass(), k -> ConcurrentHashMap.newKeySet());
        synchronized (minted) {
            taken.addAll(minted);
            String id = ContentIds.mint(typedName, candidate -> taken.contains(candidate.toLowerCase(Locale.ROOT)));
            minted.add(id.toLowerCase(Locale.ROOT));
            return id;
        }
    }

    // ---------------------------------------------------------------- assets

    /**
     * Save {@code json} as asset {@code id} of {@code store} in the pack, and load it live. An id the
     * pack already holds is overwritten in its own file; a new one is written to
     * {@code Server/<store path>/<id>.json}. The document must decode in the store, or nothing is written.
     */
    @Nonnull
    public static <T extends JsonAssetWithMap<String, M>, M extends AssetMap<String, T>> Result write(
            @Nonnull AssetStore<String, T, M> store, @Nonnull String id, @Nonnull String json) {
        try {
            if (!ContentIds.isSafe(id)) {
                return refused(id, ContentProblem.BAD_ID, "not a file name: " + id);
            }
            if (!filesInPack(store)) {
                return refused(id, ContentProblem.UNSUPPORTED_STORE, store.getAssetClass().getSimpleName() + " keeps no files");
            }
            JsonElement doc = parse(json);
            if (doc == null || !doc.isJsonObject()) {
                return refused(id, ContentProblem.BAD_JSON, "not a JSON object");
            }
            AssetPack pack = ServerContentPack.writable();
            if (pack == null) {
                return refused(id, ContentProblem.PACK_UNAVAILABLE, "the pack is " + ServerContentPack.state());
            }
            String body = ServerContentPack.pretty(doc);
            String what = "server content " + store.getAssetClass().getSimpleName() + " " + id;
            return new Result(id, AppliesWhen.NOW, AssetStoreWriter.offThread(what,
                    () -> writeNow(what, store, pack, id, body)));
        } catch (Throwable t) {
            return refused(id, ContentProblem.IO, String.valueOf(t.getMessage()));
        }
    }

    /**
     * Take asset {@code id} of {@code store} out of the pack: its file goes, and so does the pack's copy
     * in the store (another pack's copy of the same id, which this one overrode, comes back).
     */
    @Nonnull
    public static <T extends JsonAssetWithMap<String, M>, M extends AssetMap<String, T>> Result remove(
            @Nonnull AssetStore<String, T, M> store, @Nonnull String id) {
        try {
            if (!ContentIds.isSafe(id)) {
                return refused(id, ContentProblem.BAD_ID, "not a file name: " + id);
            }
            if (!filesInPack(store)) {
                return refused(id, ContentProblem.UNSUPPORTED_STORE, store.getAssetClass().getSimpleName() + " keeps no files");
            }
            AssetPack pack = ServerContentPack.writable();
            if (pack == null) {
                return refused(id, ContentProblem.PACK_UNAVAILABLE, "the pack is " + ServerContentPack.state());
            }
            String what = "server content removal of " + store.getAssetClass().getSimpleName() + " " + id;
            return new Result(id, AppliesWhen.NOW, AssetStoreWriter.offThread(what,
                    () -> removeNow(what, store, pack, id)));
        } catch (Throwable t) {
            return refused(id, ContentProblem.IO, String.valueOf(t.getMessage()));
        }
    }

    private static <T extends JsonAssetWithMap<String, M>, M extends AssetMap<String, T>> void writeNow(
            @Nonnull String what, @Nonnull AssetStore<String, T, M> store, @Nonnull AssetPack pack,
            @Nonnull String id, @Nonnull String body) {
        String packKey = pack.getName();
        try {
            store.decode(packKey, id, BsonDocument.parse(body));
        } catch (Throwable t) {
            throw new ContentRefusedException(ContentProblem.REJECTED, what + ": the store does not take it: " + t.getMessage(), t);
        }
        Path known = knownFile(store, pack, id);
        Path file = known != null ? known : storeDir(pack, store).resolve(id + extension(store));
        byte[] before;
        try {
            before = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            ServerContentPack.writeAtomically(file, body);
        } catch (IOException e) {
            throw new ContentRefusedException(ContentProblem.IO, what + ": " + file + " could not be written", e);
        }
        AssetStoreWriter.Result landed = AssetStoreWriter.writePaths(what, store, packKey, List.of(file));
        if (landed.failed()) {
            restore(what, store, packKey, file, before);
            throw new ContentRefusedException(ContentProblem.REJECTED, what + ": the store refused " + file);
        }
        ServerContentPack.watch(store, pack, storeDir(pack, store));
        forgetMinted(store, id);
    }

    /** Put the file back as it was after a load the store refused (or take a new one away), and reload that. */
    private static <T extends JsonAssetWithMap<String, M>, M extends AssetMap<String, T>> void restore(
            @Nonnull String what, @Nonnull AssetStore<String, T, M> store, @Nonnull String packKey,
            @Nonnull Path file, @Nullable byte[] before) {
        try {
            if (before == null) {
                Files.deleteIfExists(file);
                return;
            }
            ServerContentPack.writeAtomically(file, new String(before, StandardCharsets.UTF_8));
            AssetStoreWriter.writePaths(what + " (restoring the file it replaced)", store, packKey, List.of(file));
        } catch (IOException e) {
            throw new ContentRefusedException(ContentProblem.IO, what + ": " + file + " could not be put back", e);
        }
    }

    private static <T extends JsonAssetWithMap<String, M>, M extends AssetMap<String, T>> void removeNow(
            @Nonnull String what, @Nonnull AssetStore<String, T, M> store, @Nonnull AssetPack pack,
            @Nonnull String id) {
        Path file = knownFile(store, pack, id);
        if (file == null || !Files.isRegularFile(file)) {
            throw new ContentRefusedException(ContentProblem.NOT_FOUND, what + ": the pack holds no such file");
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new ContentRefusedException(ContentProblem.IO, what + ": " + file + " could not be deleted", e);
        }
        String key = store.decodeFilePathKey(file);
        AssetStoreWriter.Result landed = AssetStoreWriter.removeAssets(what, store, pack.getName(), List.of(key));
        if (landed.failed()) {
            throw new ContentRefusedException(ContentProblem.IO, what + ": the file is gone, but the store still holds it until a restart");
        }
    }

    /**
     * The pack's own file for {@code id}: the path the store recorded when it loaded the pack's copy,
     * else a file of that name under the store's folder; null when the pack has none.
     */
    @Nullable
    private static <T extends JsonAssetWithMap<String, M>, M extends AssetMap<String, T>> Path knownFile(
            @Nonnull AssetStore<String, T, M> store, @Nonnull AssetPack pack, @Nonnull String id) {
        Path dir = storeDir(pack, store);
        try {
            Map<String, Path> paths = store.getAssetMap().getPathMap(pack.getName());
            if (paths != null) {
                for (Map.Entry<String, Path> e : paths.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(id) && e.getValue().toAbsolutePath().normalize().startsWith(dir)) {
                        return e.getValue();
                    }
                }
            }
        } catch (Throwable ignored) {
            // A store that cannot list its paths is searched on disk below.
        }
        String name = id + extension(store);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.getFileName().toString().equalsIgnoreCase(name) && Files.isRegularFile(p))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static void forgetMinted(@Nonnull AssetStore<?, ?, ?> store, @Nonnull String id) {
        Set<String> minted = MINTED.get(store.getAssetClass());
        if (minted != null) {
            minted.remove(id.toLowerCase(Locale.ROOT));
        }
    }

    private static boolean filesInPack(@Nonnull AssetStore<?, ?, ?> store) {
        String path = store.getPath();
        return path != null && !path.isBlank() && !path.startsWith("..");
    }

    @Nonnull
    private static Path storeDir(@Nonnull AssetPack pack, @Nonnull AssetStore<?, ?, ?> store) {
        return pack.getRoot().resolve("Server").resolve(store.getPath()).toAbsolutePath().normalize();
    }

    @Nonnull
    private static String extension(@Nonnull AssetStore<?, ?, ?> store) {
        String ext = store.getExtension();
        return ext == null || ext.isBlank() ? ".json" : ext;
    }

    /** The lower-cased ids of the files under {@code dir} with {@code extension}. */
    @Nonnull
    private static Set<String> fileIds(@Nonnull Path dir, @Nonnull String extension) {
        Set<String> ids = new HashSet<>();
        if (!Files.isDirectory(dir)) {
            return ids;
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        try (Stream<Path> files = Files.walk(dir)) {
            files.map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(n -> n.endsWith(ext))
                    .forEach(n -> ids.add(n.substring(0, n.length() - ext.length())));
        } catch (IOException ignored) {
            // An unreadable folder adds nothing; the store's own keys still count.
        }
        return ids;
    }

    private static int countAssetFiles(@Nonnull Path root) {
        Path server = root.resolve("Server");
        if (!Files.isDirectory(server)) {
            return 0;
        }
        Path languages = server.resolve("Languages");
        try (Stream<Path> files = Files.walk(server)) {
            return (int) files.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(languages))
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    @Nullable
    private static JsonElement parse(@Nonnull String json) {
        try {
            return JsonParser.parseString(json);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- text

    /**
     * The family's text key for one field of an asset: {@code <store>.<id>.<field>}, the store named by
     * the last folder of its path, lower-cased ({@code Item/Items} is {@code items}, so an item's name is
     * the native {@code items.<Id>.name}).
     */
    @Nonnull
    public static String textKey(@Nonnull AssetStore<?, ?, ?> store, @Nonnull String id, @Nonnull String field) {
        String path = store.getPath();
        String leaf = path == null || path.isBlank() ? store.getAssetClass().getSimpleName()
                : path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
        return textKey(leaf, id, field);
    }

    /** {@code <store>.<id>.<field>}, the store lower-cased. */
    @Nonnull
    public static String textKey(@Nonnull String store, @Nonnull String id, @Nonnull String field) {
        return store.toLowerCase(Locale.ROOT) + "." + id + "." + field;
    }

    /** The key the engine registers {@code key} under, the one an asset names: {@code server.<key>}. */
    @Nonnull
    public static String storedKey(@Nonnull String key) {
        String entry = ServerContentText.entryKey(key);
        return ServerContentText.fullKey(entry != null ? entry : key.trim());
    }

    /**
     * Save {@code english} under {@code key} (a {@link #textKey}, or its {@link #storedKey}). The result's
     * id is the stored key; its {@link AppliesWhen} is {@code NOW} for a new key or unchanged words, and
     * {@code RESTART} for new words on a key the server already loaded.
     */
    @Nonnull
    public static Result setText(@Nonnull String key, @Nonnull String english) {
        Map<String, String> one = new LinkedHashMap<>();
        one.put(key, english);
        return textSet(one);
    }

    /** {@link #setText} for several keys in one save (an editor's every field), applying when the slowest does. */
    @Nonnull
    public static Result setTexts(@Nonnull Map<String, String> englishByKey) {
        if (englishByKey.isEmpty()) {
            return new Result("no texts", AppliesWhen.NOW, CompletableFuture.completedFuture(null));
        }
        return textSet(englishByKey);
    }

    @Nonnull
    private static Result textSet(@Nonnull Map<String, String> englishByKey) {
        try {
            return ServerContentText.set(englishByKey);
        } catch (Throwable t) {
            return refused("texts", ContentProblem.IO, String.valueOf(t.getMessage()));
        }
    }

    /** Take {@code key}'s words out of the pack; a key the server loaded keeps showing them until a restart. */
    @Nonnull
    public static Result removeText(@Nonnull String key) {
        try {
            return ServerContentText.remove(key);
        } catch (Throwable t) {
            return refused(key, ContentProblem.IO, String.valueOf(t.getMessage()));
        }
    }

    // ---------------------------------------------------------------- export

    /**
     * Zip the pack and its text as a normal content pack for another server's mods folder, under a new
     * name of its own (the live pack's id would collide with that server's own server content pack, and
     * the engine refuses two packs of one id). Runs on the writer thread, after every save queued before
     * it; completes with what it wrote, or exceptionally with a {@link ContentRefusedException}.
     */
    @Nonnull
    public static CompletableFuture<Export> export() {
        CompletableFuture<Export> done = new CompletableFuture<>();
        AssetStoreWriter.offThread("server content export", () -> {
            try {
                done.complete(ServerContentExport.write());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        }).whenComplete((v, t) -> {
            if (t != null) {
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    // ---------------------------------------------------------------- shared

    /** A result refused before anything was queued. */
    @Nonnull
    static Result refused(@Nonnull String id, @Nonnull ContentProblem problem, @Nonnull String detail) {
        return new Result(id, AppliesWhen.NOW,
                CompletableFuture.failedFuture(new ContentRefusedException(problem, id + ": " + detail)));
    }
}
