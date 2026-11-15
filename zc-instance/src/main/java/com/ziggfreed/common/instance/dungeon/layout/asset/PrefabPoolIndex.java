package com.ziggfreed.common.instance.dungeon.layout.asset;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
 * are read once and kept sorted, so a variant index means the same prefab to the generator and the painter.
 *
 * <p>Make a fresh one after the packs reload ({@link #live()}): it never forgets a name it has read.
 */
public final class PrefabPoolIndex implements PoolIndex {

    private final Map<String, List<String>> keys = new ConcurrentHashMap<>();

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
        String name = prefab.trim();
        if (name.isEmpty()) {
            return List.of();
        }
        return keys.computeIfAbsent(name, PrefabPoolIndex::read);
    }

    @Nonnull
    private static List<String> read(@Nonnull String name) {
        TreeSet<String> found = new TreeSet<>();
        boolean pool = name.endsWith(RoomSpec.POOL_SUFFIX);
        try {
            for (PrefabStore.AssetPackPrefabPath root : PrefabStore.get().getAllAssetPrefabPaths()) {
                Path prefabs = root.prefabsPath();
                try {
                    PrefabLoader.resolvePrefabs(prefabs, name,
                            path -> found.add(pool ? keyOf(prefabs, path) : name));
                } catch (IOException | RuntimeException e) {
                    SafeLog.warn("[dungeon] prefab pool '" + name + "' could not be read under " + prefabs + ": "
                            + e.getMessage());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            SafeLog.warn("[dungeon] prefab pool '" + name + "' could not be read: " + e.getMessage());
        }
        return List.copyOf(found);
    }

    /** A prefab file's key under {@code root}: its path in dot form, its prefab suffix dropped. */
    @Nonnull
    private static String keyOf(@Nonnull Path root, @Nonnull Path file) {
        String relative = PrefabStore.stripPrefabSuffix(root.relativize(file).toString());
        return relative.replace(File.separatorChar, '.').replace('/', '.');
    }
}
