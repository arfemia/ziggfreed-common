package com.ziggfreed.common.instance.dungeon.layout.asset;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabLoader;
import com.ziggfreed.common.instance.dungeon.layout.PoolIndex;
import com.ziggfreed.common.instance.dungeon.layout.RoomSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * The server's answer to {@link PoolIndex}: the prefabs a room's prefab name resolves to across every loaded pack's
 * {@code Server/Prefabs/}, through the engine's own resolver ({@code PrefabLoader.resolvePrefabs}, the one vanilla's
 * cave nodes use), so a pool {@code Folder.*} holds exactly the files the engine would pick from. Each name's keys
 * are read once and kept sorted, so a variant index means the same prefab to the generator and the painter; its
 * {@link #paths} are the files those keys load from, in the same order, a key two packs ship taken from the first
 * pack in the engine's load order.
 *
 * <p>Make a fresh one after the packs reload ({@link #live()}): it never forgets a name it has read. Safe to read from
 * any thread.
 */
public final class PrefabPoolIndex implements PoolIndex {

    /** One name's prefabs: its keys in dot form, sorted, and the file each loads from, in the same order. */
    private record Pool(@Nonnull List<String> keys, @Nonnull List<Path> paths) {
    }

    private final Map<String, Pool> pools = new ConcurrentHashMap<>();

    private PrefabPoolIndex() {
    }

    /** A fresh index over the packs loaded now. */
    @Nonnull
    public static PrefabPoolIndex live() {
        return new PrefabPoolIndex();
    }

    @Override
    public int variants(@Nonnull String prefab) {
        return keys(prefab).size();
    }

    /**
     * The prefab keys {@code prefab} names, in dot form and sorted: every prefab in the folder for a pool, the name
     * itself for one prefab a pack ships, none when nothing answers.
     */
    @Nonnull
    public List<String> keys(@Nonnull String prefab) {
        return pool(prefab).keys();
    }

    /**
     * The file each of {@link #keys}'s prefabs loads from, index for index (what {@code PrefabBufferUtil.getCached}
     * reads); a key two packs ship is the first pack's.
     */
    @Nonnull
    public List<Path> paths(@Nonnull String prefab) {
        return pool(prefab).paths();
    }

    @Nonnull
    private Pool pool(@Nonnull String prefab) {
        String name = prefab.trim();
        if (name.isEmpty()) {
            return new Pool(List.of(), List.of());
        }
        return pools.computeIfAbsent(name, PrefabPoolIndex::read);
    }

    @Nonnull
    private static Pool read(@Nonnull String name) {
        Map<String, Path> found = new TreeMap<>();
        boolean pool = name.endsWith(RoomSpec.POOL_SUFFIX);
        try {
            for (PrefabStore.AssetPackPrefabPath root : PrefabStore.get().getAllAssetPrefabPaths()) {
                Path prefabs = root.prefabsPath();
                try {
                    PrefabLoader.resolvePrefabs(prefabs, name,
                            path -> found.putIfAbsent(pool ? keyOf(prefabs, path) : name, path));
                } catch (IOException | RuntimeException e) {
                    SafeLog.warn("[dungeon] prefab pool '" + name + "' could not be read under " + prefabs + ": "
                            + e.getMessage());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            SafeLog.warn("[dungeon] prefab pool '" + name + "' could not be read: " + e.getMessage());
        }
        return new Pool(List.copyOf(found.keySet()), List.copyOf(new ArrayList<>(found.values())));
    }

    /** A prefab file's key under {@code root}: its path in dot form, its prefab suffix dropped. */
    @Nonnull
    private static String keyOf(@Nonnull Path root, @Nonnull Path file) {
        String relative = PrefabStore.stripPrefabSuffix(root.relativize(file).toString());
        return relative.replace(File.separatorChar, '.').replace('/', '.');
    }
}
