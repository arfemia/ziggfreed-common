package com.ziggfreed.common.content;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The server content pack's place on disk and in the engine: an ordinary folder content pack in the
 * mods folder, {@code mods/ServerContent}, made the way the Asset Editor makes a new pack
 * ({@code AssetEditorPlugin.handleCreateAssetPack}): its manifest written, the pack switched on in
 * the server config, and registered with the engine for the session that made it. From the next
 * start the engine's own scan of the mods folder loads it like any content pack: in the start's one
 * asset pass, mutable (so the file watcher and the Asset Editor may write into it), and open in the
 * Asset Editor from its first screen.
 *
 * <p><b>Why the mods folder, and why the first session registers at {@code start()}.</b> The Asset
 * Editor lists the packs present at its own setup and, after that, only packs registered once the
 * asset pass has run; every non-Hytale plugin sets up after it, and a pack registered during a
 * plugin's setup joins the asset pass without telling anyone. So a pack this library registered at
 * setup would load but never reach the editor, and one registered at start every session would load
 * after the asset pass, after anything that reads its stores at boot. A folder the engine scans has
 * neither problem; the session that creates it registers it at start, while it is still empty.
 *
 * <p><b>The owner keeps the last word.</b> The manifest's {@code Group} and {@code Name} are read
 * back from the file, so an owner may rename the pack. A folder whose pack the engine did not load
 * (turned off in the server config, or a broken manifest) stays off: nothing here switches it back on.
 *
 * <p><b>Load order.</b> The engine sorts packs by their manifests' dependencies, then by id, so a
 * pack with none loads before every mod's pack, where an asset overriding a mod's asset, or naming one
 * as its {@code Parent}, would load first and lose. At each boot every other loaded pack that does not
 * itself load after this one is merged into the manifest's {@code OptionalDependencies} (the owner's
 * entries and version ranges kept), so from the next start this pack loads last (a missing optional
 * dependency is skipped). A pack named there that has since come to load after this one would make
 * the start's sort a cycle, which the engine refuses: it comes out at the next boot, and a start that
 * already sees the loop takes this pack out of its sort and registers it at {@code start()}.
 *
 * <p><b>The file watcher.</b> The engine watches a store folder of the pack only if it is there when
 * the pack loads. A store folder a save makes later gets its watcher from {@link #watch}; one made by
 * hand or by the Asset Editor is watched from the next start.
 */
final class ServerContentPack {

    /** Where the pack's state stands. */
    enum State {
        /** {@link #ensure} has not run. */
        NOT_SET_UP,
        /** Loaded, mutable: writes land. */
        READY,
        /** Loaded, but the engine marks it immutable (a zip, or a packaged folder). */
        READ_ONLY,
        /** The folder is there, but the engine did not load it: turned off in the server config, or a broken manifest. */
        DISABLED,
        /** Made this session, registering at {@code start()}. */
        STARTING,
        /** Could not be made or registered; the log says why. */
        FAILED,
        /** No asset module on this server (a bootstrap run). */
        UNAVAILABLE
    }

    /** The folder under the mods folder. */
    static final String FOLDER = "ServerContent";

    /** The pack's identity as made; an owner may change it in the manifest. */
    static final String DEFAULT_GROUP = "Server";
    static final String DEFAULT_NAME = "ServerContent";

    static final String MANIFEST = "manifest.json";

    private static final String VERSION = "1.0.0";

    private static final String DESCRIPTION =
            "Content made on this server, in game and in the Asset Editor.";

    private static final String OPTIONAL_DEPENDENCIES = "OptionalDependencies";

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile State state = State.NOT_SET_UP;
    private static volatile String packId = DEFAULT_GROUP + ":" + DEFAULT_NAME;
    @Nullable
    private static volatile PluginManifest pendingManifest;
    @Nullable
    private static volatile Path pendingPath;

    /**
     * The pack's folders the engine's file watcher covers this session, absolute: the store folders
     * there when the pack loaded (the engine attaches a watcher only to a folder that exists then), and
     * each store folder a save made since, which {@link #watch} attached.
     */
    private static final Set<Path> WATCHED = ConcurrentHashMap.newKeySet();

    private ServerContentPack() {
    }

    /** The folder, relative to the server's working directory. */
    @Nonnull
    static Path folder() {
        return PluginManager.MODS_PATH.resolve(FOLDER);
    }

    @Nonnull
    static State state() {
        return state;
    }

    /** The pack's id ({@code Group:Name}), also the key its assets load under. */
    @Nonnull
    static String packId() {
        return packId;
    }

    /** The loaded pack, or null when the engine holds none under {@link #packId()}. */
    @Nullable
    static AssetPack loaded() {
        try {
            AssetModule am = AssetModule.get();
            return am == null ? null : am.getAssetPack(packId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The loaded pack when a write may land in it, else null. */
    @Nullable
    static AssetPack writable() {
        AssetPack pack = loaded();
        return state == State.READY && pack != null && !pack.isImmutable() ? pack : null;
    }

    /**
     * At the library's setup: find the pack the engine's scan loaded, or make the folder on a server
     * that has none (registered at {@link #registerIfStarting}). Never throws.
     */
    static void ensure() {
        try {
            AssetModule am = AssetModule.get();
            if (am == null) {
                state = State.UNAVAILABLE;
                return;
            }
            Path manifestFile = folder().resolve(MANIFEST);
            if (Files.isRegularFile(manifestFile)) {
                adoptExisting(am, manifestFile);
                return;
            }
            create(manifestFile);
        } catch (Throwable t) {
            state = State.FAILED;
            SafeLog.severe("[content] the server content pack in " + folder() + " could not be set up, so nothing"
                    + " made in game can be saved this session", t);
        }
    }

    private static void adoptExisting(@Nonnull AssetModule am, @Nonnull Path manifestFile) throws IOException {
        String id = idOf(manifestFile);
        if (id == null) {
            state = State.DISABLED;
            SafeLog.warn("[content] " + manifestFile + " names no Group and Name, so the server content pack is off;"
                    + " fix the manifest or delete it to have a new one made");
            return;
        }
        packId = id;
        AssetPack pack = am.getAssetPack(id);
        if (pack == null) {
            state = State.DISABLED;
            SafeLog.info("[content] the server content pack " + id + " in " + folder() + " is turned off in the server"
                    + " config, so nothing made in game can be saved; switch it on to save there");
            return;
        }
        if (avoidCycle(am, pack, manifestFile)) {
            return;
        }
        snapshotWatched(pack);
        state = pack.isImmutable() ? State.READ_ONLY : State.READY;
        SafeLog.info("[content] server content pack " + id + " at " + pack.getRoot() + " ("
                + (pack.isImmutable() ? "read-only" : "writable") + ")");
    }

    /**
     * Before this start sorts its packs: when the pack's manifest names a pack that now loads after
     * this one (a mod updated while the server was stopped to depend on it, or a {@code ModLoadOrder}
     * line), the sort would find a cycle and the server would refuse to start. Those names come out of
     * the manifest's {@code OptionalDependencies} for every later start, and for this one the pack
     * leaves the sort and registers at {@code start()}, after the asset pass. A required dependency
     * that loops is the owner's to remove, and is named. Answers whether it took the pack out.
     */
    private static boolean avoidCycle(@Nonnull AssetModule am, @Nonnull AssetPack pack, @Nonnull Path manifestFile)
            throws IOException {
        PluginManifest manifest = pack.getManifest();
        if (manifest == null) {
            return false;
        }
        Set<String> dependents = dependents(packId, graph(am.getAssetPacks()));
        Set<String> optional = new TreeSet<>();
        for (PluginIdentifier dep : manifest.getOptionalDependencies().keySet()) {
            optional.add(dep.toString());
        }
        Set<String> required = new TreeSet<>();
        for (PluginIdentifier dep : manifest.getDependencies().keySet()) {
            required.add(dep.toString());
        }
        optional.retainAll(dependents);
        required.retainAll(dependents);
        if (optional.isEmpty() && required.isEmpty()) {
            return false;
        }
        dropOptional(manifestFile, optional);
        // The engine's common-asset listener stops watching the pack's Common folder on an unregister,
        // and its monitor throws when that folder is missing; an empty Common folder is an ordinary part
        // of a pack.
        Files.createDirectories(pack.getRoot().resolve("Common"));
        am.unregisterPack(packId);
        pendingManifest = manifest;
        pendingPath = pack.getPackLocation();
        state = State.STARTING;
        Set<String> named = new TreeSet<>(optional);
        named.addAll(required);
        SafeLog.warn("[content] the server content pack " + packId + " names " + named
                + ", which now load after it, so this start would refuse the loop: it loads after the asset pass this"
                + " once, and its manifest no longer names " + optional + " from the next start"
                + (required.isEmpty() ? "" : "; take " + required + " out of its Dependencies by hand"));
        return true;
    }

    /** Take {@code ids} out of the manifest file's {@code OptionalDependencies}, keeping everything else. */
    private static void dropOptional(@Nonnull Path manifestFile, @Nonnull Set<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            JsonObject manifest = readJson(manifestFile);
            JsonElement deps = manifest == null ? null : manifest.get(OPTIONAL_DEPENDENCIES);
            if (deps == null || !deps.isJsonObject()) {
                return;
            }
            for (String id : ids) {
                deps.getAsJsonObject().remove(id);
            }
            writeJson(manifestFile, manifest);
        } catch (Throwable t) {
            SafeLog.warn("[content] could not take " + ids + " out of " + manifestFile, t);
        }
    }

    /** Every store folder of the pack that is there now: the ones the engine's watcher covers once the pack loads. */
    private static void snapshotWatched(@Nonnull AssetPack pack) {
        WATCHED.clear();
        Path server = pack.getRoot().resolve("Server").toAbsolutePath().normalize();
        if (pack.isImmutable() || !Files.isDirectory(server)) {
            return;
        }
        try (Stream<Path> dirs = Files.walk(server)) {
            dirs.filter(Files::isDirectory).forEach(d -> WATCHED.add(d.toAbsolutePath().normalize()));
        } catch (IOException | RuntimeException e) {
            SafeLog.fine("[content] could not list the pack's folders: " + e.getMessage());
        }
    }

    /**
     * Give a store folder a save just made its file watcher, the engine's own call for a folder that
     * appears after a pack loaded ({@code AssetStore.addFileMonitor}, as {@code AssetModule} does for a
     * store registered late), so hand edits there load at once too. Once per folder; nothing when the
     * server runs without a watcher.
     */
    static void watch(@Nonnull AssetStore<?, ?, ?> store, @Nonnull AssetPack pack, @Nonnull Path storeDir) {
        try {
            Path dir = storeDir.toAbsolutePath().normalize();
            AssetModule am = AssetModule.get();
            if (am == null || am.getAssetMonitor() == null || !Files.isDirectory(dir) || WATCHED.contains(dir)) {
                return;
            }
            store.addFileMonitor(pack.getName(), dir);
            WATCHED.add(dir);
        } catch (Throwable t) {
            SafeLog.fine("[content] no file watcher for " + storeDir + ": " + t.getMessage());
        }
    }

    /** True when the engine's file watcher covers {@code storeDir} this session. */
    static boolean watched(@Nonnull Path storeDir) {
        return WATCHED.contains(storeDir.toAbsolutePath().normalize());
    }

    private static void create(@Nonnull Path manifestFile) throws IOException {
        Files.createDirectories(manifestFile.getParent());
        JsonObject manifest = new JsonObject();
        manifest.addProperty("Group", DEFAULT_GROUP);
        manifest.addProperty("Name", DEFAULT_NAME);
        manifest.addProperty("Version", VERSION);
        manifest.addProperty("Description", DESCRIPTION);
        manifest.addProperty("ServerVersion", "*");
        writeJson(manifestFile, manifest);

        PluginManifest made = new PluginManifest();
        made.setGroup(DEFAULT_GROUP);
        made.setName(DEFAULT_NAME);
        made.setVersion(Semver.fromString(VERSION));
        made.setDescription(DESCRIPTION);
        made.setServerVersion(SemverRange.WILDCARD);
        packId = new PluginIdentifier(made).toString();
        pendingManifest = made;
        switchOnInConfig(made);
        state = State.STARTING;
        SafeLog.info("[content] made the server content pack " + packId + " in " + folder());
    }

    /** The Asset Editor's own step for a pack it makes: switched on, so the next start loads it. */
    private static void switchOnInConfig(@Nonnull PluginManifest manifest) {
        try {
            HytaleServerConfig config = HytaleServer.get().getConfig();
            HytaleServerConfig.setBoot(config, new PluginIdentifier(manifest), true);
            config.markChanged();
            if (config.consumeHasChanged()) {
                HytaleServerConfig.save(config).join();
            }
        } catch (Throwable t) {
            SafeLog.warn("[content] could not switch the server content pack on in the server config; it loads at"
                    + " the next start only while the config's DefaultModsEnabled is on", t);
        }
    }

    /**
     * At the library's start: register the pack made this session (or taken out of this start's sort),
     * which loads it into every store and tells the Asset Editor too.
     */
    static void registerIfStarting() {
        PluginManifest manifest = pendingManifest;
        if (state != State.STARTING || manifest == null) {
            return;
        }
        try {
            Path path = pendingPath != null ? pendingPath : folder();
            AssetModule am = AssetModule.get();
            boolean ok = am.registerPack(packId, path, manifest, AssetPack.PackSource.RUNTIME);
            AssetPack pack = am.getAssetPack(packId);
            state = ok && pack != null ? (pack.isImmutable() ? State.READ_ONLY : State.READY) : State.FAILED;
            pendingManifest = null;
            pendingPath = null;
            if (pack != null) {
                snapshotWatched(pack);
            }
            if (state == State.FAILED) {
                SafeLog.severe("[content] the engine refused the server content pack " + packId
                        + ", so nothing made in game can be saved this session");
            }
        } catch (Throwable t) {
            state = State.FAILED;
            SafeLog.severe("[content] the server content pack " + packId + " could not be registered", t);
        }
    }

    /**
     * At boot: merge into the manifest's {@code OptionalDependencies} every other loaded pack that
     * does not load after this one, so from the next start this pack loads last. An entry already
     * there keeps its version range, an entry naming a pack this server does not load stays, and only
     * an entry naming a pack that now loads after this one comes out (naming it would be a cycle, and
     * the engine refuses to start on one). Writes only on a change; never throws.
     */
    static void refreshLoadOrder(@Nonnull Collection<String> skip) {
        AssetPack pack = writable();
        if (pack == null) {
            return;
        }
        try {
            Path manifestFile = pack.getRoot().resolve(MANIFEST);
            JsonObject manifest = Files.isRegularFile(manifestFile) ? readJson(manifestFile) : null;
            if (manifest == null) {
                return;
            }
            List<AssetPack> packs = AssetModule.get().getAssetPacks();
            Set<String> dependents = dependents(packId, graph(packs));
            JsonElement had = manifest.get(OPTIONAL_DEPENDENCIES);
            JsonObject deps = had != null && had.isJsonObject() ? had.getAsJsonObject() : new JsonObject();
            Set<String> removed = new TreeSet<>(deps.keySet());
            removed.retainAll(dependents);
            Set<String> added = new TreeSet<>();
            for (AssetPack other : packs) {
                String id = other.getName();
                if (!id.equals(packId) && !skip.contains(id) && !dependents.contains(id) && !deps.has(id)) {
                    added.add(id);
                }
            }
            if (removed.isEmpty() && added.isEmpty()) {
                return;
            }
            for (String id : removed) {
                deps.remove(id);
            }
            for (String id : added) {
                deps.addProperty(id, "*");
            }
            manifest.remove(OPTIONAL_DEPENDENCIES);
            if (deps.size() > 0) {
                manifest.add(OPTIONAL_DEPENDENCIES, deps);
            }
            writeJson(manifestFile, manifest);
            SafeLog.info("[content] the server content pack's load order: added " + added + ", removed " + removed
                    + " (each now loads after it); it loads after " + deps.size() + " other pack(s) from the next start");
        } catch (Throwable t) {
            SafeLog.warn("[content] could not update the server content pack's load order", t);
        }
    }

    /**
     * The load-order graph the engine sorts, as each pack id to the ids it loads after: every loaded
     * pack's dependencies (optional or not), each {@code LoadBefore} turned round, and the server
     * config's {@code ModLoadOrder}.
     */
    @Nonnull
    private static Map<String, Set<String>> graph(@Nonnull List<AssetPack> packs) {
        Map<String, Set<String>> after = new HashMap<>();
        Set<String> loadedIds = new HashSet<>();
        for (AssetPack other : packs) {
            loadedIds.add(other.getName());
            addEdges(after, other);
        }
        try {
            addManualOrder(after, loadedIds, HytaleServer.get().getConfig().getModLoadOrder());
        } catch (Throwable t) {
            SafeLog.fine("[content] no ModLoadOrder read: " + t.getMessage());
        }
        return after;
    }

    /** What {@code pack} loads after: its dependencies, optional or not, and each pack whose LoadBefore names it. */
    private static void addEdges(@Nonnull Map<String, Set<String>> after, @Nonnull AssetPack pack) {
        PluginManifest manifest = pack.getManifest();
        if (manifest == null) {
            return;
        }
        String id = pack.getName();
        Set<String> mine = after.computeIfAbsent(id, k -> new HashSet<>());
        for (PluginIdentifier dep : manifest.getDependencies().keySet()) {
            mine.add(dep.toString());
        }
        for (PluginIdentifier dep : manifest.getOptionalDependencies().keySet()) {
            mine.add(dep.toString());
        }
        for (PluginIdentifier target : manifest.getLoadBefore().keySet()) {
            after.computeIfAbsent(target.toString(), k -> new HashSet<>()).add(id);
        }
    }

    /**
     * The owner's {@code ModLoadOrder} from the server config, as the engine reads it: among the ids
     * that are loaded, each one loads after the one listed before it.
     */
    private static void addManualOrder(@Nonnull Map<String, Set<String>> after, @Nonnull Set<String> loadedIds,
            @Nullable PluginIdentifier[] order) {
        if (order == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (PluginIdentifier entry : order) {
            String id = entry == null ? null : entry.toString();
            if (id == null || !loadedIds.contains(id) || !seen.add(id)) {
                continue;
            }
            if (previous != null) {
                after.computeIfAbsent(id, k -> new HashSet<>()).add(previous);
            }
            previous = id;
        }
    }

    /**
     * Every node of {@code after} (an id to the ids it loads after) that loads after {@code self},
     * directly or through others: naming one of those from {@code self} would be a cycle, and the
     * engine refuses to start on one. {@code self}'s own edges are ignored, since they are what a caller
     * decides. Pure.
     */
    @Nonnull
    static Set<String> dependents(@Nonnull String self, @Nonnull Map<String, Set<String>> after) {
        Map<String, Set<String>> before = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : after.entrySet()) {
            if (e.getKey().equals(self)) {
                continue;
            }
            for (String dep : e.getValue()) {
                before.computeIfAbsent(dep, k -> new HashSet<>()).add(e.getKey());
            }
        }
        Set<String> dependents = new HashSet<>();
        Deque<String> walk = new ArrayDeque<>();
        walk.add(self);
        while (!walk.isEmpty()) {
            for (String next : before.getOrDefault(walk.poll(), Set.of())) {
                if (!next.equals(self) && dependents.add(next)) {
                    walk.add(next);
                }
            }
        }
        return dependents;
    }

    /** {@code Group:Name} from a manifest file, or null when it names neither. */
    @Nullable
    static String idOf(@Nonnull Path manifestFile) {
        JsonObject manifest = readJson(manifestFile);
        if (manifest == null) {
            return null;
        }
        String group = text(manifest, "Group");
        String name = text(manifest, "Name");
        return group == null || name == null ? null : group + ":" + name;
    }

    /** The live manifest's JSON with a new {@code Name}, for an export that must not collide with the live pack. */
    @Nullable
    static JsonObject manifestRenamed(@Nonnull Path manifestFile, @Nonnull String name, @Nonnull String description) {
        JsonObject manifest = readJson(manifestFile);
        if (manifest == null) {
            return null;
        }
        manifest.addProperty("Name", name);
        manifest.addProperty("Description", description);
        return manifest;
    }

    @Nullable
    private static String text(@Nonnull JsonObject json, @Nonnull String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        String s = value.getAsString().trim();
        return s.isEmpty() || s.contains(":") ? null : s;
    }

    @Nullable
    private static JsonObject readJson(@Nonnull Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nonnull
    static String pretty(@Nonnull JsonElement json) {
        return PRETTY.toJson(json) + "\n";
    }

    private static void writeJson(@Nonnull Path file, @Nonnull JsonElement json) throws IOException {
        writeAtomically(file, pretty(json));
    }

    /**
     * Write {@code text} to a temporary file and move it into place, so no reader ever sees half a file.
     * The temporary file sits in the library's own folder, outside every watched pack folder: the
     * engine's file watcher lets a path through once it is no longer a file, so a temporary file made
     * beside the asset and moved away would reach the store as an asset to load.
     */
    static void writeAtomically(@Nonnull Path file, @Nonnull String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path staging = OwnerFiles.DIRECTORY.toAbsolutePath();
        Files.createDirectories(staging);
        Path tmp = Files.createTempFile(staging, "server-content-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
