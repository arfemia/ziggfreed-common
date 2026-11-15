package com.ziggfreed.common.instance.dungeon.layout;

import javax.annotation.Nonnull;

/**
 * The seam the pure generator asks how many interchangeable prefabs a room's prefab names: the server answers it
 * from the prefab folders every loaded pack ships ({@code layout/asset/PrefabPoolIndex}), a test with a fake.
 */
@FunctionalInterface
public interface PoolIndex {

    /**
     * How many variants {@code prefab} names, in a fixed order the painter shares: every prefab in the folder for a
     * pool ({@code Folder.*}), 1 for a single prefab a pack ships, 0 when nothing answers (the room is never placed).
     */
    int variants(@Nonnull String prefab);
}
